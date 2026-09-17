package com.example.storeflow.service;

import com.example.storeflow.dto.AllocationDTO;
import com.example.storeflow.dto.OptimizationBatchDTO;
import com.example.storeflow.dto.OptimizationBatchItemDTO;
import com.example.storeflow.dto.OptimizationResultDTO;
import com.example.storeflow.entity.BatchStatus;
import com.example.storeflow.entity.FlowScenario;
import com.example.storeflow.entity.OptimizationBatch;
import com.example.storeflow.entity.OptimizationBatchItem;
import com.example.storeflow.entity.StaffAllocation;
import com.example.storeflow.entity.StoreArea;
import com.example.storeflow.exception.BusinessConflictException;
import com.example.storeflow.exception.ResourceNotFoundException;
import com.example.storeflow.repository.FlowScenarioRepository;
import com.example.storeflow.repository.OptimizationBatchItemRepository;
import com.example.storeflow.repository.OptimizationBatchRepository;
import com.example.storeflow.repository.StaffAllocationRepository;
import com.example.storeflow.util.LoadCalculationUtil;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 优化落地批次会签领域服务（现场经理口径：先把这一轮冻成批次，测算岗确认数字、
 * 现场经理再签收，两步都点头才能改场景）。
 *
 * <p>关键不变量：
 * <ol>
 *   <li><b>每轮推演必冻批次</b>：推演跑出来的同一事务里，把当时每块区域的核定容量、
 *       编制配额、优化前分配、优化后分配冻结进批次项；之后区域档案怎么改都不回写。</li>
 *   <li><b>两步会签缺一不可</b>：测算岗确认只改批次状态，绝不动场景分配；
 *       现场经理签收与「场景切成优化后方案」在同一事务里一次做完。</li>
 *   <li><b>轮次作废</b>：确认（或待确认）期间又跑了新一轮推演，旧批次立即 STALE，
 *       测算确认一并作废；签收被拒时失败说明点名批次号、冻结轮次、场景当前轮次。</li>
 *   <li><b>并发串行</b>：确认/签收/推演都对场景行加悲观写锁，同一份批次的落地只发生一次。</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OptimizationBatchService {

    private static final List<BatchStatus> OPEN_STATUSES =
            List.of(BatchStatus.PENDING_CONFIRM, BatchStatus.CONFIRMED);

    private final OptimizationBatchRepository batchRepository;
    private final OptimizationBatchItemRepository itemRepository;
    private final FlowScenarioRepository scenarioRepository;
    private final StaffAllocationRepository allocationRepository;
    private final ClosureService closureService;

    @PersistenceContext
    private EntityManager entityManager;

    // ============================ 推演冻结批次（随推演事务） ============================

    /**
     * 把本轮推演冻成一份落地批次（在推演事务内调用）。
     *
     * <p>一次做完两件事：作废旧批次（新一轮推演产生后，仍待确认/待签收的批次连同
     * 测算确认一并作废）；再冻结本轮快照为新批次（PENDING_CONFIRM）。
     */
    @Transactional
    public OptimizationBatch freezeRunBatch(FlowScenario scenario, int runSeq,
                                            List<AllocationDTO> beforeDTOs,
                                            List<AllocationDTO> afterDTOs,
                                            Map<Long, StoreArea> areaMap,
                                            OptimizationResultDTO result) {
        // 1) 新一轮推演产生：旧批次（含测算岗已确认的）立即作废，确认不得跨轮次沿用。
        List<OptimizationBatch> openBatches =
                batchRepository.findByScenarioIdAndStatusIn(scenario.getId(), OPEN_STATUSES);
        for (OptimizationBatch old : openBatches) {
            old.setStatus(BatchStatus.STALE);
            old.setStaleReason("第 " + runSeq + " 轮推演已产生，本批次冻结的第 " + old.getRunSeq() + " 轮已过期"
                    + (old.getConfirmedAt() != null ? "，测算岗确认一并作废" : "")
                    + "；请基于最新一轮推演的批次重新会签。");
            batchRepository.save(old);
            log.info("落地批次#{}作废：{}", old.getId(), old.getStaleReason());
        }

        // 2) 冻结本轮快照：批次头 + 逐区域档案（核定容量/编制配额/优化前后分配）。
        OptimizationBatch batch = new OptimizationBatch();
        batch.setScenarioId(scenario.getId());
        batch.setScenarioName(scenario.getScenarioName());
        batch.setRunSeq(runSeq);
        batch.setStatus(BatchStatus.PENDING_CONFIRM);
        batch.setBeforeMaxSaturation(result.getBeforeMaxSaturation());
        batch.setAfterMaxSaturation(result.getAfterMaxSaturation());
        batch.setBeforeOverloadedCount(result.getBeforeOverloadedCount());
        batch.setAfterOverloadedCount(result.getAfterOverloadedCount());
        OptimizationBatch saved = batchRepository.save(batch);

        Map<Long, AllocationDTO> afterByArea = afterDTOs.stream()
                .collect(Collectors.toMap(AllocationDTO::getAreaId, a -> a, (a, b) -> a));
        List<OptimizationBatchItem> items = new ArrayList<>();
        for (AllocationDTO before : beforeDTOs) {
            AllocationDTO after = afterByArea.get(before.getAreaId());
            StoreArea area = areaMap.get(before.getAreaId());
            if (after == null || area == null) {
                continue;
            }
            OptimizationBatchItem item = new OptimizationBatchItem();
            item.setBatchId(saved.getId());
            item.setAreaId(before.getAreaId());
            item.setAreaName(before.getAreaName() != null ? before.getAreaName() : area.getAreaName());
            item.setMaxCapacity(area.getMaxCapacity());
            item.setStaffQuota(area.getStaffQuota());
            item.setBeforeStaff(before.getAllocatedStaff());
            item.setBeforeFlow(before.getAllocatedFlow());
            item.setAfterStaff(after.getAllocatedStaff());
            item.setAfterFlow(after.getAllocatedFlow());
            items.add(item);
        }
        itemRepository.saveAll(items);

        log.info("第{}轮推演冻成落地批次#{}（场景{}，{}块区域），待测算岗确认",
                runSeq, saved.getId(), scenario.getScenarioName(), items.size());
        return saved;
    }

    // ============================ 测算岗确认（不动场景） ============================

    /**
     * 测算岗确认数字。只推进批次状态到 CONFIRMED，场景当前分配一行不动——
     * 确认之后、现场经理签收之前，场景必须仍是旧方案。
     */
    @Transactional
    public OptimizationBatchDTO confirm(Long batchId, String operator) {
        LockedBatch locked = lockBatchAndScenario(batchId);
        OptimizationBatch batch = locked.batch;
        FlowScenario scenario = locked.scenario;

        if (batch.getStatus() == BatchStatus.APPLIED) {
            throw new BusinessConflictException("BATCH_ALREADY_APPLIED",
                    "落地批次 #" + batchId + " 已会签落地，无需再确认。");
        }
        assertNotStale(batch, scenario);
        if (batch.getStatus() == BatchStatus.CONFIRMED) {
            throw new BusinessConflictException("BATCH_ALREADY_CONFIRMED",
                    "落地批次 #" + batchId + " 测算岗已确认（" + batch.getConfirmedBy()
                            + "），待现场经理签收，不能重复确认。");
        }

        batch.setStatus(BatchStatus.CONFIRMED);
        batch.setConfirmedBy(operatorOrDefault(operator, "测算岗"));
        batch.setConfirmedAt(LocalDateTime.now());
        batchRepository.save(batch);
        log.info("落地批次#{}测算岗确认（{}），待现场经理签收；场景分配未动", batchId, batch.getConfirmedBy());
        return toDTO(batch, scenario);
    }

    // ============================ 现场经理签收并落地（同事务切场景） ============================

    /**
     * 现场经理签收。会签闭环与场景切换一笔事务一次做完：
     * 批次置 APPLIED 的同时，把场景当前分配逐区域写回批次冻结的优化后方案。
     * 任何一步失败整体回滚，绝不留下「批次落地了场景没改」或「场景改了没批次对账」。
     */
    @Transactional
    public OptimizationBatchDTO sign(Long batchId, String operator) {
        LockedBatch locked = lockBatchAndScenario(batchId);
        OptimizationBatch batch = locked.batch;
        FlowScenario scenario = locked.scenario;

        if (batch.getStatus() == BatchStatus.APPLIED) {
            throw new BusinessConflictException("BATCH_ALREADY_APPLIED",
                    "落地批次 #" + batchId + " 已会签落地（签收人 " + batch.getSignedBy()
                            + "），不能重复签收。");
        }
        assertNotStale(batch, scenario);
        if (batch.getStatus() != BatchStatus.CONFIRMED) {
            throw new BusinessConflictException("BATCH_NOT_CONFIRMED",
                    "落地批次 #" + batchId + " 尚未经测算岗确认，现场经理不能签收；"
                            + "测算确认、经理签收两步会签缺一不可。");
        }

        List<OptimizationBatchItem> items = itemRepository.findByBatchIdOrderByAreaIdAsc(batchId);

        // 封区守卫：批次涉及的区域若已被临时封区（清 0 禁写回），整单拒绝落地。
        Set<Long> closedAreaIds = closureService.liveClosedAreaIds(scenario.getId());
        List<String> blockedAreas = items.stream()
                .filter(i -> closedAreaIds.contains(i.getAreaId()))
                .map(OptimizationBatchItem::getAreaName)
                .collect(Collectors.toList());
        if (!blockedAreas.isEmpty()) {
            throw new BusinessConflictException("BATCH_BLOCKED_AREA_CLOSED",
                    "落地批次 #" + batchId + " 涉及的区域「" + String.join("、", blockedAreas)
                            + "」当前处于临时封区生效状态，禁止写回人员/客流；"
                            + "请先解封或由值班长作废封区单，再签收落地。");
        }

        // 落地第一件事：场景当前分配切成批次冻结的优化后方案。
        Map<Long, StaffAllocation> baseByArea = allocationRepository.lockBaseAllocations(scenario.getId())
                .stream().collect(Collectors.toMap(StaffAllocation::getAreaId, a -> a));
        for (OptimizationBatchItem item : items) {
            StaffAllocation alloc = baseByArea.get(item.getAreaId());
            if (alloc == null) {
                alloc = new StaffAllocation();
                alloc.setScenarioId(scenario.getId());
                alloc.setAreaId(item.getAreaId());
                alloc.setIsOptimized(false);
                baseByArea.put(item.getAreaId(), alloc);
            }
            alloc.setAllocatedStaff(item.getAfterStaff());
            alloc.setAllocatedFlow(item.getAfterFlow());
            allocationRepository.save(alloc);
        }
        scenario.setAppliedRunSeq(batch.getRunSeq());
        scenarioRepository.save(scenario);

        // 落地第二件事：会签闭环留痕。
        LocalDateTime now = LocalDateTime.now();
        batch.setStatus(BatchStatus.APPLIED);
        batch.setSignedBy(operatorOrDefault(operator, "现场经理"));
        batch.setSignedAt(now);
        batch.setAppliedAt(now);
        batchRepository.save(batch);

        log.info("落地批次#{}会签完成（测算岗{} / 现场经理{}），场景「{}」已切换到第{}轮优化后方案",
                batchId, batch.getConfirmedBy(), batch.getSignedBy(),
                scenario.getScenarioName(), batch.getRunSeq());
        return toDTO(batch, scenario);
    }

    // ============================ 查询 ============================

    @Transactional(readOnly = true)
    public List<OptimizationBatchDTO> list(Long scenarioId) {
        List<OptimizationBatch> batches = scenarioId == null
                ? batchRepository.findAllByOrderByIdDesc()
                : batchRepository.findByScenarioIdOrderByIdDesc(scenarioId);
        Map<Long, FlowScenario> scenarios = new HashMap<>();
        for (FlowScenario s : scenarioRepository.findAllById(
                batches.stream().map(OptimizationBatch::getScenarioId).collect(Collectors.toSet()))) {
            scenarios.put(s.getId(), s);
        }
        List<OptimizationBatchDTO> dtos = new ArrayList<>();
        for (OptimizationBatch b : batches) {
            dtos.add(toDTO(b, scenarios.get(b.getScenarioId())));
        }
        return dtos;
    }

    @Transactional(readOnly = true)
    public OptimizationBatchDTO getById(Long id) {
        OptimizationBatch batch = getBatchOrThrow(id);
        FlowScenario scenario = scenarioRepository.findById(batch.getScenarioId()).orElse(null);
        return toDTO(batch, scenario);
    }

    // ============================ 私有辅助 ============================

    /**
     * 轮次守卫：批次已 STALE，或批次冻结的轮次落后于场景当前轮次（确认后又跑了新推演），
     * 都拒绝继续会签。失败说明必须让人看见：哪一份批次、它冻的是哪一轮、场景现在是哪一轮。
     */
    private void assertNotStale(OptimizationBatch batch, FlowScenario scenario) {
        int currentRunSeq = scenario.getCurrentRunSeq() == null ? 0 : scenario.getCurrentRunSeq();
        boolean stale = batch.getStatus() == BatchStatus.STALE
                || !batch.getRunSeq().equals(currentRunSeq);
        if (!stale) {
            return;
        }
        if (batch.getStatus() != BatchStatus.STALE) {
            // 兜底：批次还挂在会签流程里但轮次已过期，就地作废留痕（正常路径下推演时已作废）。
            batch.setStatus(BatchStatus.STALE);
            batch.setStaleReason("场景已跑到第 " + currentRunSeq + " 轮推演，本批次冻结的第 "
                    + batch.getRunSeq() + " 轮已过期，测算确认作废。");
            batchRepository.save(batch);
        }
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("batchId", batch.getId());
        details.put("frozenRunSeq", batch.getRunSeq());
        details.put("currentRunSeq", currentRunSeq);
        throw new BusinessConflictException("BATCH_STALE",
                "落地批次 #" + batch.getId() + " 冻的是第 " + batch.getRunSeq()
                        + " 轮推演，场景「" + scenario.getScenarioName() + "」当前已跑到第 "
                        + currentRunSeq + " 轮；该批次的测算确认已作废，现场经理不能再用它改场景。"
                        + "请基于最新一轮推演生成的批次重新会签。",
                details);
    }

    private OptimizationBatch getBatchOrThrow(Long id) {
        return batchRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("落地批次不存在: " + id));
    }

    /**
     * 会签操作的加锁入口：先凭批次拿到场景 id（scenarioId 不可变，允许非锁读），
     * 对场景行加悲观写锁（与推演/封区/另一次会签严格串行），再在锁内把批次
     * 刷新到最新状态——保证「检查状态 → 推进状态」之间没有并发窗口，
     * 同一份批次的确认/落地只发生一次。
     *
     * <p>先 flush 再 refresh：flush 把本事务未提交的批次改动（如同一事务里
     * 先 confirm 后 sign）推到库内，refresh 读到的是自己刚推进的状态；
     * 跨事务场景下 refresh 读到的是持锁前其他事务已提交的最新状态。
     */
    private LockedBatch lockBatchAndScenario(Long batchId) {
        OptimizationBatch probe = getBatchOrThrow(batchId);
        FlowScenario scenario = lockScenario(probe.getScenarioId());
        entityManager.flush();
        entityManager.refresh(probe);
        return new LockedBatch(probe, scenario);
    }

    /** 场景行锁 + 锁内刷新后的批次。 */
    private static final class LockedBatch {
        final OptimizationBatch batch;
        final FlowScenario scenario;

        LockedBatch(OptimizationBatch batch, FlowScenario scenario) {
            this.batch = batch;
            this.scenario = scenario;
        }
    }

    private FlowScenario lockScenario(Long scenarioId) {
        return scenarioRepository.lockScenario(scenarioId)
                .orElseThrow(() -> new ResourceNotFoundException("客流场景不存在: " + scenarioId));
    }

    private String operatorOrDefault(String operator, String defaultRole) {
        return operator != null && !operator.isBlank() ? operator.trim() : defaultRole;
    }

    private OptimizationBatchDTO toDTO(OptimizationBatch b, FlowScenario scenario) {
        OptimizationBatchDTO dto = new OptimizationBatchDTO();
        dto.setId(b.getId());
        dto.setScenarioId(b.getScenarioId());
        dto.setScenarioName(b.getScenarioName());
        dto.setRunSeq(b.getRunSeq());
        dto.setStatus(b.getStatus().name());
        dto.setStatusText(statusText(b.getStatus()));
        dto.setConfirmedBy(b.getConfirmedBy());
        dto.setConfirmedAt(b.getConfirmedAt());
        dto.setSignedBy(b.getSignedBy());
        dto.setSignedAt(b.getSignedAt());
        dto.setAppliedAt(b.getAppliedAt());
        dto.setStaleReason(b.getStaleReason());
        dto.setBeforeMaxSaturation(b.getBeforeMaxSaturation());
        dto.setAfterMaxSaturation(b.getAfterMaxSaturation());
        dto.setBeforeOverloadedCount(b.getBeforeOverloadedCount());
        dto.setAfterOverloadedCount(b.getAfterOverloadedCount());
        dto.setCreatedAt(b.getCreatedAt());
        dto.setUpdatedAt(b.getUpdatedAt());
        if (scenario != null) {
            dto.setScenarioCurrentRunSeq(scenario.getCurrentRunSeq());
            dto.setScenarioAppliedRunSeq(scenario.getAppliedRunSeq());
        }
        List<OptimizationBatchItemDTO> items = itemRepository.findByBatchIdOrderByAreaIdAsc(b.getId())
                .stream().map(this::toItemDTO).collect(Collectors.toList());
        dto.setItems(items);
        return dto;
    }

    private OptimizationBatchItemDTO toItemDTO(OptimizationBatchItem item) {
        OptimizationBatchItemDTO dto = new OptimizationBatchItemDTO();
        dto.setAreaId(item.getAreaId());
        dto.setAreaName(item.getAreaName());
        dto.setMaxCapacity(item.getMaxCapacity());
        dto.setStaffQuota(item.getStaffQuota());
        dto.setBeforeStaff(item.getBeforeStaff());
        dto.setBeforeFlow(item.getBeforeFlow());
        dto.setAfterStaff(item.getAfterStaff());
        dto.setAfterFlow(item.getAfterFlow());
        dto.setBeforeSaturation(LoadCalculationUtil.calculateSaturationRate(
                item.getBeforeFlow(), item.getMaxCapacity()));
        dto.setAfterSaturation(LoadCalculationUtil.calculateSaturationRate(
                item.getAfterFlow(), item.getMaxCapacity()));
        return dto;
    }

    private String statusText(BatchStatus s) {
        switch (s) {
            case PENDING_CONFIRM: return "待测算岗确认";
            case CONFIRMED: return "测算已确认·待经理签收";
            case APPLIED: return "会签完成·已落地";
            case STALE: return "已作废·被新一轮推演取代";
            default: return s.name();
        }
    }
}
