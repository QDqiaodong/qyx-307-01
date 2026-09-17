package com.example.storeflow.service;

import com.example.storeflow.dto.BatchFailureDTO;
import com.example.storeflow.dto.BatchItemDTO;
import com.example.storeflow.dto.OptimizationBatchDTO;
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
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 推演落地批次与会签领域服务。
 *
 * <p>口径（现场经理）：数字对了也不能立刻改场景。一轮推演先冻成一份批次，测算岗确认数字、
 * 现场经理再签收，<b>两步都点头的同一笔事务</b>才把场景在用分配改成批次冻住的优化后方案。
 *
 * <p>关键不变量：
 * <ol>
 *   <li><b>冻结不可变</b>：批次明细冻住推演当时每块区域的核定容量、编制配额、优化前/后分配；
 *       之后区域额度改小也不改写批次数字。</li>
 *   <li><b>会签闸门</b>：测算确认（DRAFT→CONFIRMED）只改批次状态；现场签收（CONFIRMED→LANDED）
 *       才在同一事务写回在用分配。CONFIRMED 未签收期间场景分配必须仍是旧方案。</li>
 *   <li><b>新轮作废</b>：又跑一轮推演，上一轮在途批次（含已确认）置 SUPERSEDED，
 *       现场经理拿旧批次签收会被 409 拒绝，回执写清哪份批次、冻的哪轮、场景现在哪轮。</li>
 *   <li><b>原子落账</b>：签收时任一块优化后区域被封区生效、或当前额度放不下冻住的优化后数字，
 *       整单拒绝、场景一行不动。</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OptimizationBatchService {

    private static final List<BatchStatus> LIVE_STATUSES = List.of(BatchStatus.DRAFT, BatchStatus.CONFIRMED);

    private final OptimizationBatchRepository batchRepository;
    private final OptimizationBatchItemRepository itemRepository;
    private final StaffAllocationRepository allocationRepository;
    private final FlowScenarioRepository scenarioRepository;
    private final StoreAreaService areaService;
    private final ClosureService closureService;

    // ============================ 新一轮推演：作废旧批次 + 冻结批次 ============================

    /**
     * 新一轮推演开始时调用（已持场景行锁）：把该场景所有在途批次置为 SUPERSEDED。
     * 测算岗此前在旧批次上的确认一并作废。
     */
    @Transactional
    public void supersedeLiveBatchesForNewRound(FlowScenario scenario) {
        int currentRound = scenario.getOptimizationRound() == null ? 0 : scenario.getOptimizationRound();
        int newRound = currentRound + 1;
        // 行锁锁住在途批次，避免与「现场签收」并发交错。
        List<OptimizationBatch> live = batchRepository.lockLiveByScenarioId(scenario.getId(), LIVE_STATUSES);
        for (OptimizationBatch b : live) {
            b.setStatus(BatchStatus.SUPERSEDED);
            b.setSupersedeReason(String.format(
                    "场景「%s」已执行第%d轮推演，本批次冻结的是第%d轮，确认作废，不能再据其落地。",
                    scenario.getScenarioName(), newRound, b.getOptimizationRound()));
            batchRepository.save(b);
            log.info("落地批次#{}因第{}轮推演作废（冻的是第{}轮）", b.getId(), newRound, b.getOptimizationRound());
        }
    }

    /**
     * 从场景最新一轮推演草稿冻结一份落地批次（推演事务内调用）。
     * 幂等：若该轮已存在批次（例如同一事务重入），直接返回最新一份，不重复冻结。
     */
    @Transactional
    public OptimizationBatchDTO freezeFromLatestRound(Long scenarioId) {
        FlowScenario scenario = scenarioRepository.findById(scenarioId)
                .orElseThrow(() -> new ResourceNotFoundException("场景不存在: " + scenarioId));
        int round = scenario.getOptimizationRound() == null ? 0 : scenario.getOptimizationRound();

        List<OptimizationBatch> existing =
                batchRepository.findByScenarioIdAndOptimizationRoundOrderByIdDesc(scenarioId, round);
        if (!existing.isEmpty()) {
            return toDTO(existing.get(0), scenario);
        }

        List<StaffAllocation> drafts = allocationRepository
                .findByScenarioIdAndIsOptimizedAndOptimizationRound(scenarioId, true, round);
        if (drafts.isEmpty()) {
            throw new ResourceNotFoundException("第" + round + "轮推演草稿不存在，无法冻结落地批次");
        }

        OptimizationBatch batch = new OptimizationBatch();
        batch.setScenarioId(scenarioId);
        batch.setOptimizationRound(round);
        batch.setStatus(BatchStatus.DRAFT);
        batch.setScenarioName(scenario.getScenarioName());

        int beforeStaff = 0, beforeFlow = 0, afterStaff = 0, afterFlow = 0;
        Map<Long, String> areaNames = areaService.getAllAreas().stream()
                .collect(Collectors.toMap(StoreArea::getId, StoreArea::getAreaName));
        List<OptimizationBatchItem> items = new ArrayList<>();
        for (StaffAllocation d : drafts) {
            OptimizationBatchItem item = new OptimizationBatchItem();
            item.setScenarioId(scenarioId);
            item.setAreaId(d.getAreaId());
            int cap = d.getFrozenMaxCapacity() != null ? d.getFrozenMaxCapacity() : 0;
            int quota = d.getFrozenStaffQuota() != null ? d.getFrozenStaffQuota() : 0;
            item.setAreaName(areaNames.getOrDefault(d.getAreaId(), "区域#" + d.getAreaId()));
            item.setFrozenMaxCapacity(cap);
            item.setFrozenStaffQuota(quota);
            item.setBeforeStaff(d.getBeforeStaff() != null ? d.getBeforeStaff() : d.getAllocatedStaff());
            item.setBeforeFlow(d.getBeforeFlow() != null ? d.getBeforeFlow() : d.getAllocatedFlow());
            item.setAfterStaff(d.getAllocatedStaff());
            item.setAfterFlow(d.getAllocatedFlow());
            items.add(item);

            beforeStaff += item.getBeforeStaff();
            beforeFlow += item.getBeforeFlow();
            afterStaff += item.getAfterStaff();
            afterFlow += item.getAfterFlow();
        }
        items.sort((x, y) -> Long.compare(x.getAreaId(), y.getAreaId()));

        batch.setBeforeTotalStaff(beforeStaff);
        batch.setBeforeTotalFlow(beforeFlow);
        batch.setAfterTotalStaff(afterStaff);
        batch.setAfterTotalFlow(afterFlow);
        OptimizationBatch saved = batchRepository.save(batch);

        for (OptimizationBatchItem item : items) {
            item.setBatchId(saved.getId());
            itemRepository.save(item);
        }
        fillOverview(saved, items);
        batchRepository.save(saved);

        log.info("场景{}第{}轮推演冻结落地批次#{}（{}块区域）", scenarioId, round, saved.getId(), items.size());
        return toDTO(saved, scenario);
    }

    private void fillOverview(OptimizationBatch batch, List<OptimizationBatchItem> items) {
        double beforeMax = 0, afterMax = 0, beforeSum = 0, afterSum = 0;
        int beforeOver = 0, afterOver = 0;
        for (OptimizationBatchItem it : items) {
            double bs = LoadCalculationUtil.calculateSaturationRate(it.getBeforeFlow(), it.getFrozenMaxCapacity());
            double as = LoadCalculationUtil.calculateSaturationRate(it.getAfterFlow(), it.getFrozenMaxCapacity());
            beforeMax = Math.max(beforeMax, bs);
            afterMax = Math.max(afterMax, as);
            beforeSum += bs;
            afterSum += as;
            if (Boolean.TRUE.equals(LoadCalculationUtil.isOverloaded(bs))) {
                beforeOver++;
            }
            if (Boolean.TRUE.equals(LoadCalculationUtil.isOverloaded(as))) {
                afterOver++;
            }
        }
        int n = Math.max(1, items.size());
        batch.setBeforeMaxSaturation(beforeMax);
        batch.setAfterMaxSaturation(afterMax);
        batch.setBeforeAvgSaturation(Math.round(beforeSum / n * 100.0) / 100.0);
        batch.setAfterAvgSaturation(Math.round(afterSum / n * 100.0) / 100.0);
        batch.setBeforeOverloadedCount(beforeOver);
        batch.setAfterOverloadedCount(afterOver);
    }

    // ============================ 会签：测算确认 / 现场签收 ============================

    /** 测算岗确认数字：只允许 DRAFT 且仍是最新一轮的批次；确认只改批次，不碰场景分配。 */
    @Transactional
    public OptimizationBatchDTO analystConfirm(Long batchId, String note) {
        FlowScenario scenario = lockScenarioOf(batchId);
        OptimizationBatch batch = lockBatch(batchId);
        assertStillCurrent(batch, scenario);
        if (batch.getStatus() == BatchStatus.CONFIRMED) {
            return toDTO(batch, scenario); // 幂等：已确认不重复处理
        }
        if (batch.getStatus() != BatchStatus.DRAFT) {
            throw new BusinessConflictException("BATCH_NOT_LIVE",
                    "批次#" + batchId + "当前状态「" + statusText(batch.getStatus()) + "」，测算岗无法再确认。");
        }
        batch.setStatus(BatchStatus.CONFIRMED);
        batch.setAnalystConfirmedAt(LocalDateTime.now());
        batch.setAnalystNote(note);
        batchRepository.save(batch);
        log.info("落地批次#{}测算岗已确认（场景分配仍为旧方案，待现场经理签收）", batchId);
        return toDTO(batch, scenario);
    }

    /**
     * 现场经理签收：两步会签的第二步。同一笔事务内
     * （1）校验批次 CONFIRMED 且仍是最新一轮；（2）把在用分配改成批次冻住的优化后方案。
     * 任一前置不满足或落账会超额/落到封控区域，整单 409、场景一行不动。
     */
    @Transactional
    public OptimizationBatchDTO managerSign(Long batchId, String note) {
        FlowScenario scenario = lockScenarioOf(batchId);
        OptimizationBatch batch = lockBatch(batchId);
        assertStillCurrent(batch, scenario);
        if (batch.getStatus() == BatchStatus.LANDED) {
            return toDTO(batch, scenario); // 幂等
        }
        if (batch.getStatus() == BatchStatus.DRAFT) {
            throw new BusinessConflictException("BATCH_NOT_CONFIRMED",
                    "批次#" + batchId + "测算岗尚未确认，现场经理不能先行签收落地。");
        }
        if (batch.getStatus() != BatchStatus.CONFIRMED) {
            throw new BusinessConflictException("BATCH_NOT_LIVE",
                    "批次#" + batchId + "当前状态「" + statusText(batch.getStatus()) + "」，不能签收落地。");
        }

        Long scenarioId = batch.getScenarioId();
        List<OptimizationBatchItem> items = itemRepository.findByBatchIdOrderByAreaIdAsc(batchId);

        // 当前仍生效封区的区域：优化后方案不能把人员/客流写回这些区域。
        Set<Long> closedAreaIds = closureService.liveClosedAreaIds(scenarioId);
        List<String> blockedClosed = items.stream()
                .filter(i -> closedAreaIds.contains(i.getAreaId())
                        && (i.getAfterStaff() > 0 || i.getAfterFlow() > 0))
                .map(i -> "「" + i.getAreaName() + "」当前封区生效（优化后人" + i.getAfterStaff()
                        + "/流" + i.getAfterFlow() + "）")
                .collect(Collectors.toList());
        if (!blockedClosed.isEmpty()) {
            throw new BusinessConflictException("BATCH_LANDING_BLOCKED",
                    "批次#" + batchId + "无法落地：" + String.join("、", blockedClosed)
                            + "。请先解封/作废封区单，或重新推演一轮再走会签，场景分配保持旧方案。",
                    buildFailure(batch, scenario, "BATCH_LANDING_BLOCKED",
                            "批次含当前封区生效区域，优化后方案写不回去"));
        }

        // 当前区域额度（不是冻住的额度）必须放得下优化后数字，否则不能改场景。
        Map<Long, StoreArea> currentAreas = areaService.getAllAreas().stream()
                .collect(Collectors.toMap(StoreArea::getId, a -> a));
        List<String> overQuota = new ArrayList<>();
        for (OptimizationBatchItem i : items) {
            StoreArea area = currentAreas.get(i.getAreaId());
            if (area == null) {
                overQuota.add("「" + i.getAreaName() + "」已被删除");
                continue;
            }
            if (i.getAfterStaff() > area.getStaffQuota()) {
                overQuota.add("「" + area.getAreaName() + "」优化后人员" + i.getAfterStaff()
                        + " 超当前编制" + area.getStaffQuota());
            }
            if (i.getAfterFlow() > area.getMaxCapacity()) {
                overQuota.add("「" + area.getAreaName() + "」优化后客流" + i.getAfterFlow()
                        + " 超当前容量" + area.getMaxCapacity());
            }
        }
        if (!overQuota.isEmpty()) {
            throw new BusinessConflictException("BATCH_LANDING_BLOCKED",
                    "批次#" + batchId + "无法落地：" + String.join("、", overQuota)
                            + "。冻住的优化后数字已超当前核定额度，场景分配保持旧方案。",
                    buildFailure(batch, scenario, "BATCH_LANDING_BLOCKED",
                            "批次冻住的优化后数字超出当前额度"));
        }

        // —— 会签第二步通过：在同一事务把在用分配改成优化后方案 ——
        List<StaffAllocation> baseRows = allocationRepository.lockBaseAllocations(scenarioId);
        Map<Long, StaffAllocation> baseByArea = baseRows.stream()
                .collect(Collectors.toMap(StaffAllocation::getAreaId, a -> a));
        for (OptimizationBatchItem i : items) {
            StaffAllocation row = baseByArea.get(i.getAreaId());
            if (row == null) {
                row = new StaffAllocation();
                row.setScenarioId(scenarioId);
                row.setAreaId(i.getAreaId());
                row.setIsOptimized(false);
            }
            row.setAllocatedStaff(i.getAfterStaff());
            row.setAllocatedFlow(i.getAfterFlow());
            allocationRepository.save(row);
        }

        batch.setStatus(BatchStatus.LANDED);
        batch.setManagerSignedAt(LocalDateTime.now());
        batch.setLandedAt(LocalDateTime.now());
        batch.setManagerNote(note);
        batchRepository.save(batch);

        // 优化后草稿已正式成为在用分配，草稿表清空（数字已永久冻在批次明细里可复查）。
        allocationRepository.deleteByScenarioIdAndIsOptimized(scenarioId, true);

        log.info("落地批次#{}经现场经理签收落地：场景{}在用分配已改为第{}轮优化后方案",
                batchId, scenarioId, batch.getOptimizationRound());
        return toDTO(batch, scenario);
    }

    // ============================ 查询 ============================

    @Transactional(readOnly = true)
    public List<OptimizationBatchDTO> list(Long scenarioId) {
        List<OptimizationBatch> batches = scenarioId == null
                ? batchRepository.findAll()
                : batchRepository.findByScenarioIdOrderByOptimizationRoundDescIdDesc(scenarioId);
        Map<Long, FlowScenario> scenarios = loadScenarios(
                batches.stream().map(OptimizationBatch::getScenarioId).collect(Collectors.toSet()));
        List<OptimizationBatchDTO> dtos = new ArrayList<>();
        for (OptimizationBatch b : batches) {
            dtos.add(toDTO(b, scenarios.get(b.getScenarioId())));
        }
        return dtos;
    }

    @Transactional(readOnly = true)
    public OptimizationBatchDTO getById(Long id) {
        OptimizationBatch b = batchRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("落地批次不存在: " + id));
        FlowScenario scenario = scenarioRepository.findById(b.getScenarioId()).orElse(null);
        return toDTO(b, scenario);
    }

    // ============================ 私有辅助 ============================

    private OptimizationBatch lockBatch(Long id) {
        return batchRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("落地批次不存在: " + id));
    }

    private FlowScenario lockScenarioOf(Long batchId) {
        OptimizationBatch b = batchRepository.findById(batchId)
                .orElseThrow(() -> new ResourceNotFoundException("落地批次不存在: " + batchId));
        return scenarioRepository.lockById(b.getScenarioId())
                .orElseThrow(() -> new ResourceNotFoundException("场景不存在: " + b.getScenarioId()));
    }

    /** 轮次守卫：批次冻的轮次必须仍是场景当前最新一轮，否则一律拒绝（含已确认未签收）。 */
    private void assertStillCurrent(OptimizationBatch batch, FlowScenario scenario) {
        int currentRound = scenario.getOptimizationRound() == null ? 0 : scenario.getOptimizationRound();
        if (batch.getOptimizationRound() != null
                && batch.getOptimizationRound() < currentRound
                && batch.getStatus() != BatchStatus.LANDED) {
            String msg = String.format(
                    "落地批次#%d冻的是场景「%s」第%d轮推演，场景上现在已经是第%d轮；"
                            + "该批次确认已作废，不能再拿它改场景。",
                    batch.getId(), scenario.getScenarioName(),
                    batch.getOptimizationRound(), currentRound);
            throw new BusinessConflictException("BATCH_SUPERSEDED", msg,
                    buildFailure(batch, scenario, "BATCH_SUPERSEDED", msg));
        }
    }

    private BatchFailureDTO buildFailure(OptimizationBatch batch, FlowScenario scenario,
                                         String code, String message) {
        BatchFailureDTO f = new BatchFailureDTO();
        f.setCode(code);
        f.setBatchId(batch.getId());
        f.setScenarioId(batch.getScenarioId());
        f.setFrozenRound(batch.getOptimizationRound());
        f.setCurrentRound(scenario != null && scenario.getOptimizationRound() != null
                ? scenario.getOptimizationRound() : batch.getOptimizationRound());
        f.setStatus(batch.getStatus() != null ? batch.getStatus().name() : null);
        f.setMessage(message);
        return f;
    }

    private Map<Long, FlowScenario> loadScenarios(Set<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return new HashMap<>();
        }
        return scenarioRepository.findAllById(ids).stream()
                .collect(Collectors.toMap(FlowScenario::getId, s -> s));
    }

    private OptimizationBatchDTO toDTO(OptimizationBatch b, FlowScenario scenario) {
        OptimizationBatchDTO dto = new OptimizationBatchDTO();
        dto.setId(b.getId());
        dto.setScenarioId(b.getScenarioId());
        dto.setScenarioName(b.getScenarioName());
        dto.setOptimizationRound(b.getOptimizationRound());
        int currentRound = scenario != null && scenario.getOptimizationRound() != null
                ? scenario.getOptimizationRound()
                : Optional.ofNullable(b.getOptimizationRound()).orElse(0);
        dto.setCurrentRound(currentRound);
        dto.setStatus(b.getStatus().name());
        dto.setStatusText(statusText(b.getStatus()));
        boolean current = b.getOptimizationRound() != null && b.getOptimizationRound() >= currentRound;
        dto.setCurrent(current);
        dto.setLive(b.getStatus().isLive() && current);
        dto.setCanAnalystConfirm(b.getStatus() == BatchStatus.DRAFT && current);
        dto.setCanManagerSign(b.getStatus() == BatchStatus.CONFIRMED && current);

        dto.setBeforeMaxSaturation(b.getBeforeMaxSaturation());
        dto.setAfterMaxSaturation(b.getAfterMaxSaturation());
        dto.setBeforeAvgSaturation(b.getBeforeAvgSaturation());
        dto.setAfterAvgSaturation(b.getAfterAvgSaturation());
        dto.setBeforeOverloadedCount(b.getBeforeOverloadedCount());
        dto.setAfterOverloadedCount(b.getAfterOverloadedCount());
        dto.setBeforeTotalStaff(b.getBeforeTotalStaff());
        dto.setBeforeTotalFlow(b.getBeforeTotalFlow());
        dto.setAfterTotalStaff(b.getAfterTotalStaff());
        dto.setAfterTotalFlow(b.getAfterTotalFlow());

        List<BatchItemDTO> itemDTOs = itemRepository.findByBatchIdOrderByAreaIdAsc(b.getId()).stream()
                .map(this::toItemDTO).collect(Collectors.toList());
        dto.setItems(itemDTOs);

        dto.setAnalystConfirmedAt(b.getAnalystConfirmedAt());
        dto.setManagerSignedAt(b.getManagerSignedAt());
        dto.setLandedAt(b.getLandedAt());
        dto.setAnalystNote(b.getAnalystNote());
        dto.setManagerNote(b.getManagerNote());
        dto.setSupersedeReason(b.getSupersedeReason());
        if (b.getStatus() == BatchStatus.SUPERSEDED
                || (b.getStatus().isLive() && !current)) {
            dto.setFailure(buildFailure(b, scenario, "BATCH_SUPERSEDED",
                    b.getSupersedeReason() != null ? b.getSupersedeReason()
                            : "该批次冻的轮次已不是场景最新一轮"));
        }
        dto.setCreatedAt(b.getCreatedAt());
        dto.setUpdatedAt(b.getUpdatedAt());
        return dto;
    }

    private BatchItemDTO toItemDTO(OptimizationBatchItem i) {
        BatchItemDTO d = new BatchItemDTO();
        d.setId(i.getId());
        d.setAreaId(i.getAreaId());
        d.setAreaName(i.getAreaName());
        d.setFrozenMaxCapacity(i.getFrozenMaxCapacity());
        d.setFrozenStaffQuota(i.getFrozenStaffQuota());
        d.setBeforeStaff(i.getBeforeStaff());
        d.setBeforeFlow(i.getBeforeFlow());
        d.setAfterStaff(i.getAfterStaff());
        d.setAfterFlow(i.getAfterFlow());
        double bs = LoadCalculationUtil.calculateSaturationRate(i.getBeforeFlow(), i.getFrozenMaxCapacity());
        double as = LoadCalculationUtil.calculateSaturationRate(i.getAfterFlow(), i.getFrozenMaxCapacity());
        d.setBeforeSaturationRate(bs);
        d.setAfterSaturationRate(as);
        d.setBeforeOverloaded(LoadCalculationUtil.isOverloaded(bs));
        d.setAfterOverloaded(LoadCalculationUtil.isOverloaded(as));
        return d;
    }

    private String statusText(BatchStatus s) {
        switch (s) {
            case DRAFT: return "待测算确认";
            case CONFIRMED: return "测算已确认·待现场签收";
            case SUPERSEDED: return "已被新一轮推演作废";
            case LANDED: return "已会签落地";
            default: return s.name();
        }
    }
}
