package com.example.storeflow.service;

import com.example.storeflow.dto.*;
import com.example.storeflow.entity.*;
import com.example.storeflow.event.AreaQuotaChangedEvent;
import com.example.storeflow.exception.BusinessConflictException;
import com.example.storeflow.exception.ResourceNotFoundException;
import com.example.storeflow.repository.*;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 临时封区回灌领域服务（安全岗口径：回灌落不成账，就绝不能关区）。
 *
 * <p>关键不变量：
 * <ol>
 *   <li><b>原子性</b>：提交封区在单个事务内完成「被封区域清 0 + 回灌落到开放区域 + 写封区单」。
 *       承接不下时只落一条 FAILED 回执，场景旧分配一行不动。</li>
 *   <li><b>并发串行</b>：先对场景行加悲观写锁，同一场景的两单封区严格串行，
 *       抢同一块仅剩开放区域时只有一单成功。</li>
 *   <li><b>不超额</b>：人员、客流分别按承接区域剩余编制/容量做整数精确拆分，
 *       任何承接区域回灌后都不超过核定容量/编制。</li>
 *   <li><b>生效封锁</b>：仍生效期间被封区域不得手工写回，也不作为优化调入目标。</li>
 *   <li><b>额度冲突</b>：承接区域额度改小且回灌数字超额，封区单进 QUOTA_CONFLICT，
 *       禁止解封、只能作废；台账数字不改。</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ClosureService {

    private static final List<ClosureStatus> LIVE_STATUSES =
            List.of(ClosureStatus.EFFECTIVE, ClosureStatus.QUOTA_CONFLICT);

    private final AreaClosureRepository closureRepository;
    private final ClosureInjectionRepository injectionRepository;
    private final StaffAllocationRepository allocationRepository;
    private final StoreAreaRepository areaRepository;
    private final FlowScenarioRepository scenarioRepository;
    private final ObjectMapper objectMapper;

    // ============================ 提交封区（原子） ============================

    @Transactional
    public AreaClosureDTO submitClosure(ClosureSubmitRequest request) {
        Long scenarioId = request.getScenarioId();
        Long areaId = request.getAreaId();

        // 1) 锁场景行：同一场景的并发封区在此严格串行化。
        FlowScenario scenario = closureRepository.lockScenario(scenarioId)
                .orElseThrow(() -> new ResourceNotFoundException("客流场景不存在: " + scenarioId));

        StoreArea closedArea = areaRepository.findById(areaId)
                .orElseThrow(() -> new ResourceNotFoundException("被封区域不存在: " + areaId));

        // 2) 锁该场景在用分配行 + 全部区域行（后者也串行化“区域额度改小”并发）。
        List<StaffAllocation> lockedBase = allocationRepository.lockBaseAllocations(scenarioId);
        List<Long> allAreaIds = areaRepository.findAll().stream()
                .map(StoreArea::getId).sorted().collect(Collectors.toList());
        Map<Long, StoreArea> areaMap = areaRepository.lockAllByIdInOrder(allAreaIds).stream()
                .collect(Collectors.toMap(StoreArea::getId, a -> a));

        // 3) 仍生效封区单 / 被封区域集合。
        List<AreaClosure> liveClosures = closureRepository.findLiveByScenarioId(scenarioId, LIVE_STATUSES);
        Set<Long> liveClosedAreaIds = liveClosures.stream()
                .map(AreaClosure::getAreaId).collect(Collectors.toSet());

        if (liveClosedAreaIds.contains(areaId)) {
            throw new BusinessConflictException("AREA_ALREADY_CLOSED",
                    "区域「" + closedArea.getAreaName() + "」在该场景已有仍生效的封区单，不能重复封区");
        }

        // 4) 被封区域当前人员/客流（待疏散量）。
        StaffAllocation ownAlloc = lockedBase.stream()
                .filter(a -> a.getAreaId().equals(areaId)).findFirst().orElse(null);
        int evacuatedStaff = ownAlloc != null && ownAlloc.getAllocatedStaff() != null
                ? ownAlloc.getAllocatedStaff() : 0;
        int evacuatedFlow = ownAlloc != null && ownAlloc.getAllocatedFlow() != null
                ? ownAlloc.getAllocatedFlow() : 0;

        // 5) 仍开放承接区域（排除本单要封的、和之前已生效封住的）。
        List<Long> liveClosureIds = liveClosures.stream().map(AreaClosure::getId).collect(Collectors.toList());
        List<ClosureInjection> liveInjections = liveClosureIds.isEmpty()
                ? List.of()
                : injectionRepository.findByScenarioIdOrderByIdAsc(scenarioId).stream()
                    .filter(i -> liveClosureIds.contains(i.getClosureId()))
                    .collect(Collectors.toList());
        Map<Long, Set<Long>> occupiedClosuresByReceiver = new HashMap<>();
        for (ClosureInjection in : liveInjections) {
            occupiedClosuresByReceiver
                    .computeIfAbsent(in.getReceiverAreaId(), k -> new TreeSet<>())
                    .add(in.getClosureId());
        }

        List<Receiver> receivers = new ArrayList<>();
        for (Long rid : allAreaIds) {
            if (rid.equals(areaId) || liveClosedAreaIds.contains(rid)) {
                continue;
            }
            StoreArea area = areaMap.get(rid);
            if (area == null) {
                continue;
            }
            StaffAllocation base = lockedBase.stream()
                    .filter(a -> a.getAreaId().equals(rid)).findFirst().orElse(null);
            int staffBefore = base != null && base.getAllocatedStaff() != null ? base.getAllocatedStaff() : 0;
            int flowBefore = base != null && base.getAllocatedFlow() != null ? base.getAllocatedFlow() : 0;
            receivers.add(new Receiver(area, staffBefore, flowBefore,
                    Math.max(0, area.getStaffQuota() - staffBefore),
                    Math.max(0, area.getMaxCapacity() - flowBefore)));
        }
        receivers.sort(Comparator.comparing(r -> r.area.getId()));

        int totalRemainingStaff = receivers.stream().mapToInt(r -> r.remainingStaff).sum();
        int totalRemainingFlow = receivers.stream().mapToInt(r -> r.remainingFlow).sum();
        int shortStaff = Math.max(0, evacuatedStaff - totalRemainingStaff);
        int shortFlow = Math.max(0, evacuatedFlow - totalRemainingFlow);
        boolean noOpenArea = receivers.isEmpty();
        boolean infeasible = noOpenArea && (evacuatedStaff > 0 || evacuatedFlow > 0);
        infeasible |= shortStaff > 0 || shortFlow > 0;

        // 6) 承接不下：只落 FAILED 回执，旧分配一行不动，直接返回（事务正常提交）。
        if (infeasible) {
            ClosureFailureDTO receipt = buildFailure(scenarioId, closedArea, evacuatedStaff, evacuatedFlow,
                    totalRemainingStaff, totalRemainingFlow, shortStaff, shortFlow, noOpenArea,
                    receivers, occupiedClosuresByReceiver);
            AreaClosure failed = new AreaClosure();
            failed.setScenarioId(scenarioId);
            failed.setAreaId(areaId);
            failed.setAreaName(closedArea.getAreaName());
            failed.setReason(request.getReason());
            failed.setStatus(ClosureStatus.FAILED);
            failed.setEvacuatedStaff(evacuatedStaff);
            failed.setEvacuatedFlow(evacuatedFlow);
            failed.setInjectedStaff(0);
            failed.setInjectedFlow(0);
            failed.setFailShortStaff(shortStaff);
            failed.setFailShortFlow(shortFlow);
            failed.setFailReceipt(writeJson(receipt));
            closureRepository.save(failed);
            log.warn("封区失败[场景{} 区域{}]: 人员缺{} 客流缺{}，区域保持可营业",
                    scenarioId, closedArea.getAreaName(), shortStaff, shortFlow);
            return toDTO(failed, scenario, List.of(), receipt, null);
        }

        // 7) 可行：先试算回灌拆分（纯函数），人员/客流分别按剩余额度拆。
        List<Integer> staffCaps = receivers.stream().map(r -> r.remainingStaff).collect(Collectors.toList());
        List<Integer> flowCaps = receivers.stream().map(r -> r.remainingFlow).collect(Collectors.toList());
        List<Integer> staffPlan = InjectionPlanner.splitByCapacity(evacuatedStaff, staffCaps);
        List<Integer> flowPlan = InjectionPlanner.splitByCapacity(evacuatedFlow, flowCaps);

        for (int i = 0; i < receivers.size(); i++) {
            Receiver r = receivers.get(i);
            if (staffPlan.get(i) > r.remainingStaff || flowPlan.get(i) > r.remainingFlow) {
                // 防御性：算法保证不会发生，发生即整体回滚绝不能半落账
                throw new IllegalStateException("回灌拆分超出承接区域剩余额度，事务回滚");
            }
        }

        // 8) 落账（与写封区单同一事务）。
        // 8.1 被封区域清 0（没有分配行也显式落一条 0 行）。
        if (ownAlloc != null) {
            ownAlloc.setAllocatedStaff(0);
            ownAlloc.setAllocatedFlow(0);
            allocationRepository.save(ownAlloc);
        } else {
            StaffAllocation zero = new StaffAllocation();
            zero.setScenarioId(scenarioId);
            zero.setAreaId(areaId);
            zero.setAllocatedStaff(0);
            zero.setAllocatedFlow(0);
            zero.setIsOptimized(false);
            allocationRepository.save(zero);
        }

        List<ClosureInjection> injections = new ArrayList<>();
        int injectedStaff = 0;
        int injectedFlow = 0;
        for (int i = 0; i < receivers.size(); i++) {
            Receiver r = receivers.get(i);
            int addStaff = staffPlan.get(i);
            int addFlow = flowPlan.get(i);
            if (addStaff <= 0 && addFlow <= 0) {
                continue;
            }
            StaffAllocation base = lockedBase.stream()
                    .filter(a -> a.getAreaId().equals(r.area.getId())).findFirst().orElse(null);
            if (base != null) {
                base.setAllocatedStaff(r.staffBefore + addStaff);
                base.setAllocatedFlow(r.flowBefore + addFlow);
                allocationRepository.save(base);
            } else {
                StaffAllocation nb = new StaffAllocation();
                nb.setScenarioId(scenarioId);
                nb.setAreaId(r.area.getId());
                nb.setAllocatedStaff(r.staffBefore + addStaff);
                nb.setAllocatedFlow(r.flowBefore + addFlow);
                nb.setIsOptimized(false);
                allocationRepository.save(nb);
            }

            ClosureInjection in = new ClosureInjection();
            in.setScenarioId(scenarioId);
            in.setReceiverAreaId(r.area.getId());
            in.setReceiverAreaName(r.area.getAreaName());
            in.setInjectedStaff(addStaff);
            in.setInjectedFlow(addFlow);
            in.setStaffBefore(r.staffBefore);
            in.setFlowBefore(r.flowBefore);
            in.setRemainingStaffAtTime(r.remainingStaff);
            in.setRemainingFlowAtTime(r.remainingFlow);
            injections.add(in);

            injectedStaff += addStaff;
            injectedFlow += addFlow;
        }

        // 8.2 提交后总账二次校验：总量一致 + 无任何区域超额。
        if (injectedStaff != evacuatedStaff || injectedFlow != evacuatedFlow) {
            throw new IllegalStateException("回灌总量与疏散总量不一致，事务回滚: 人员"
                    + injectedStaff + "/" + evacuatedStaff + " 客流" + injectedFlow + "/" + evacuatedFlow);
        }
        assertNoOverload(scenarioId, areaId, areaMap);

        // 8.3 写生效封区单 + 台账。
        AreaClosure closure = new AreaClosure();
        closure.setScenarioId(scenarioId);
        closure.setAreaId(areaId);
        closure.setAreaName(closedArea.getAreaName());
        closure.setReason(request.getReason());
        closure.setStatus(ClosureStatus.EFFECTIVE);
        closure.setEvacuatedStaff(evacuatedStaff);
        closure.setEvacuatedFlow(evacuatedFlow);
        closure.setInjectedStaff(injectedStaff);
        closure.setInjectedFlow(injectedFlow);
        AreaClosure savedClosure = closureRepository.save(closure);

        for (ClosureInjection in : injections) {
            in.setClosureId(savedClosure.getId());
            injectionRepository.save(in);
        }

        log.info("封区生效[场景{} 封区单#{} 区域{}]: 疏散人员{}客流{}，承接区域{}块",
                scenarioId, savedClosure.getId(), closedArea.getAreaName(),
                evacuatedStaff, evacuatedFlow, injections.size());
        return toDTO(savedClosure, scenario, injections, null, null);
    }

    // ============================ 查询 ============================

    @Transactional(readOnly = true)
    public List<AreaClosureDTO> list(Long scenarioId) {
        List<AreaClosure> closures = scenarioId == null
                ? closureRepository.findAllByOrderByCreatedAtDesc()
                : closureRepository.findByScenarioIdOrderByCreatedAtDesc(scenarioId);
        Map<Long, String> scenarioNames = scenarioNames(
                closures.stream().map(AreaClosure::getScenarioId).collect(Collectors.toSet()));
        List<AreaClosureDTO> dtos = new ArrayList<>();
        for (AreaClosure c : closures) {
            dtos.add(toDTO(c, scenarioNames.get(c.getScenarioId())));
        }
        return dtos;
    }

    @Transactional(readOnly = true)
    public AreaClosureDTO getById(Long id) {
        AreaClosure closure = closureRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("封区单不存在: " + id));
        return toDTO(closure, null);
    }

    // ============================ 作废 / 解封 ============================

    /**
     * 值班长手工作废。被封区域恢复可营业，但回灌到承接区域的人员/客流不撤回，
     * 以免和当前场景里后来的优化推演打架。EFFECTIVE / QUOTA_CONFLICT 都可作废。
     */
    @Transactional
    public AreaClosureDTO voidClosure(Long id, String note) {
        closureRepository.lockScenario(getClosureOrThrow(id).getScenarioId()); // 与封区提交互斥
        AreaClosure closure = getClosureOrThrow(id);
        if (closure.getStatus() != ClosureStatus.EFFECTIVE
                && closure.getStatus() != ClosureStatus.QUOTA_CONFLICT) {
            throw new BusinessConflictException("CLOSURE_NOT_LIVE",
                    "只有仍生效的封区单才能作废，当前状态: " + statusText(closure.getStatus()));
        }
        closure.setStatus(ClosureStatus.VOIDED);
        closure.setTerminalNote(note);
        closure.setQuotaConflict(null); // 作废即不再以冲突状态展示；回灌仍保留
        closureRepository.save(closure);
        log.info("封区单#{}作废（回灌保留不撤回）: {}", id, note);
        return toDTO(closure, null);
    }

    /**
     * 正常解封（仅 EFFECTIVE 可用）。撤回本单回灌、把被封区域恢复到封区前快照。
     * QUOTA_CONFLICT 时解封按钮不可用，只能作废；若当前场景已放不下撤回量，也拒绝解封。
     */
    @Transactional
    public AreaClosureDTO reopenClosure(Long id, String note) {
        AreaClosure closure = getClosureOrThrow(id);
        Long scenarioId = closure.getScenarioId();

        closureRepository.lockScenario(scenarioId);
        List<Long> allAreaIds = areaRepository.findAll().stream()
                .map(StoreArea::getId).sorted().collect(Collectors.toList());
        Map<Long, StoreArea> areaMap = areaRepository.lockAllByIdInOrder(allAreaIds).stream()
                .collect(Collectors.toMap(StoreArea::getId, a -> a));

        if (closure.getStatus() == ClosureStatus.QUOTA_CONFLICT) {
            throw new BusinessConflictException("UNSEAL_BLOCKED_QUOTA_CONFLICT",
                    "封区单#" + id + " 处于「额度冲突、禁止解封」，请先手工作废（作废不撤回回灌）");
        }
        if (closure.getStatus() != ClosureStatus.EFFECTIVE) {
            throw new BusinessConflictException("CLOSURE_NOT_EFFECTIVE",
                    "只有生效中的封区单才能解封，当前状态: " + statusText(closure.getStatus()));
        }

        // 一次加锁读取该场景当前在用分配行，得到数据库当前已提交值（承接区含本单此前回灌）。
        Map<Long, StaffAllocation> currentBase = allocationRepository.lockBaseAllocations(scenarioId).stream()
                .collect(Collectors.toMap(StaffAllocation::getAreaId, a -> a));

        // 1) 从承接区域撤回本单台账回灌（只减本单的量），在内存中修改托管实体。
        List<ClosureInjection> ledger = injectionRepository.findByClosureIdOrderByIdAsc(id);
        for (ClosureInjection in : ledger) {
            StaffAllocation base = currentBase.get(in.getReceiverAreaId());
            if (base == null) {
                throw new BusinessConflictException("UNSEAL_UNSAFE",
                        "承接区域「" + in.getReceiverAreaName() + "」缺少在用分配行，无法安全撤回，请改用作废");
            }
            int curStaff = base.getAllocatedStaff() != null ? base.getAllocatedStaff() : 0;
            int curFlow = base.getAllocatedFlow() != null ? base.getAllocatedFlow() : 0;
            if (curStaff < in.getInjectedStaff() || curFlow < in.getInjectedFlow()) {
                throw new BusinessConflictException("UNSEAL_UNSAFE",
                        "承接区域「" + in.getReceiverAreaName() + "」当前在场量已低于本单回灌量，"
                                + "直接撤回会与后续手工/推演调整打架，请改用作废（回灌保留）");
            }
            base.setAllocatedStaff(curStaff - in.getInjectedStaff());
            base.setAllocatedFlow(curFlow - in.getInjectedFlow());
        }

        // 2) 被封区域恢复封区前快照（需仍满足该区域当前额度）。
        StoreArea closedArea = areaMap.get(closure.getAreaId());
        if (closedArea == null) {
            throw new ResourceNotFoundException("被封区域已不存在: " + closure.getAreaId());
        }
        if (closure.getEvacuatedStaff() > closedArea.getStaffQuota()
                || closure.getEvacuatedFlow() > closedArea.getMaxCapacity()) {
            throw new BusinessConflictException("UNSEAL_UNSAFE",
                    "被封区域「" + closedArea.getAreaName() + "」当前额度已容不下封区前人员/客流，"
                            + "无法安全解封，请改用作废（回灌保留）");
        }
        StaffAllocation closedBase = currentBase.get(closure.getAreaId());
        if (closedBase == null) {
            closedBase = new StaffAllocation();
            closedBase.setScenarioId(scenarioId);
            closedBase.setAreaId(closure.getAreaId());
            closedBase.setIsOptimized(false);
            allocationRepository.save(closedBase);
            currentBase.put(closure.getAreaId(), closedBase);
        }
        closedBase.setAllocatedStaff(closure.getEvacuatedStaff());
        closedBase.setAllocatedFlow(closure.getEvacuatedFlow());

        // 3) 提交前对内存中最终分配做容量/编制校验，不再额外查库（避免与托管实体合并冲突）。
        for (StaffAllocation a : currentBase.values()) {
            StoreArea area = areaMap.get(a.getAreaId());
            if (area == null) {
                continue;
            }
            if (a.getAllocatedStaff() > area.getStaffQuota()
                    || a.getAllocatedFlow() > area.getMaxCapacity()) {
                throw new BusinessConflictException("UNSEAL_UNSAFE",
                        "解封后区域「" + area.getAreaName() + "」会超额（人员"
                                + a.getAllocatedStaff() + "/" + area.getStaffQuota() + " 客流"
                                + a.getAllocatedFlow() + "/" + area.getMaxCapacity() + "），请改用作废");
            }
        }

        closure.setStatus(ClosureStatus.REOPENED);
        closure.setTerminalNote(note);
        closureRepository.save(closure);
        log.info("封区单#{}解封，已撤回回灌并恢复区域「{}」", id, closure.getAreaName());
        return toDTO(closure, null);
    }

    // ============================ 对其他领域的守卫 / 视图支持 ============================

    /** 手工写回守卫：仍生效封区区域，不允许手工把人员/客流写回去。 */
    public void assertCanWriteAllocation(Long scenarioId, Long areaId) {
        if (scenarioId == null || areaId == null) {
            return;
        }
        long live = closureRepository.countLiveByScenarioAndArea(scenarioId, areaId, LIVE_STATUSES);
        if (live > 0) {
            throw new BusinessConflictException("AREA_CLOSED",
                    "该区域在当前客流场景中处于临时封区生效状态，已清 0 且禁止手工写回；"
                            + "请先解封或由值班长作废封区单。");
        }
    }

    /** 某场景当前仍被封住的区域 id（负荷/优化推演据此排除调入目标）。 */
    @Transactional(readOnly = true)
    public Set<Long> liveClosedAreaIds(Long scenarioId) {
        return closureRepository.findLiveByScenarioId(scenarioId, LIVE_STATUSES).stream()
                .map(AreaClosure::getAreaId)
                .collect(Collectors.toSet());
    }

    /**
     * 区域额度改小事件（与区域更新同事务）：检查所有仍生效、且回灌到该区域的封区单，
     * 若其台账回灌数字超过新容量/编制，则把封区单置为 QUOTA_CONFLICT（台账数字不改）。
     */
    @EventListener
    @Transactional
    public void onAreaQuotaChanged(AreaQuotaChangedEvent event) {
        Long areaId = event.getAreaId();
        int newQuota = event.getNewStaffQuota();
        int newCapacity = event.getNewMaxCapacity();
        boolean onlyShrink = event.getNewStaffQuota() <= event.getOldStaffQuota()
                && event.getNewMaxCapacity() <= event.getOldMaxCapacity();
        if (!onlyShrink) {
            // 额度没有任何一项改小，不可能产生新的超额冲突
            return;
        }

        List<AreaClosure> live = closureRepository.findAllLive(LIVE_STATUSES);
        if (live.isEmpty()) {
            return;
        }
        List<Long> liveIds = live.stream().map(AreaClosure::getId).collect(Collectors.toList());
        List<ClosureInjection> intoArea =
                injectionRepository.findByReceiverAreaIdAndClosureIdIn(areaId, liveIds);
        if (intoArea.isEmpty()) {
            return;
        }

        StoreArea area = areaRepository.findById(areaId).orElse(null);
        String areaName = area != null ? area.getAreaName() : ("区域#" + areaId);

        for (ClosureInjection in : intoArea) {
            int staffOverflow = Math.max(0, in.getInjectedStaff() - newQuota);
            int flowOverflow = Math.max(0, in.getInjectedFlow() - newCapacity);
            if (staffOverflow <= 0 && flowOverflow <= 0) {
                continue;
            }
            AreaClosure closure = live.stream()
                    .filter(c -> c.getId().equals(in.getClosureId())).findFirst().orElse(null);
            if (closure == null || !closure.getStatus().isLive()) {
                continue;
            }
            QuotaConflictDTO.ConflictArea ca = new QuotaConflictDTO.ConflictArea();
            ca.setReceiverAreaId(areaId);
            ca.setReceiverAreaName(areaName);
            ca.setInjectedStaff(in.getInjectedStaff());
            ca.setInjectedFlow(in.getInjectedFlow());
            ca.setNewStaffQuota(newQuota);
            ca.setNewMaxCapacity(newCapacity);
            ca.setStaffOverflow(staffOverflow > 0 ? staffOverflow : null);
            ca.setFlowOverflow(flowOverflow > 0 ? flowOverflow : null);

            QuotaConflictDTO qc = readJson(closure.getQuotaConflict(), QuotaConflictDTO.class);
            if (qc == null || qc.getConflicts() == null) {
                qc = new QuotaConflictDTO();
                qc.setConflicts(new ArrayList<>());
            }
            // 同一承接区域去重更新
            qc.getConflicts().removeIf(x -> x.getReceiverAreaId().equals(areaId));
            qc.getConflicts().add(ca);
            qc.setMessage("承接区域「" + areaName + "」核定额度被改小，封区单回灌数字超额，"
                    + "已进入「额度冲突、禁止解封」，需值班长手工作废。");
            closure.setStatus(ClosureStatus.QUOTA_CONFLICT);
            closure.setQuotaConflict(writeJson(qc));
            closureRepository.save(closure);
            log.warn("封区单#{}进入额度冲突：承接区域{} 人员超{} 客流超{}（新编制{}/新容量{}）",
                    closure.getId(), areaName, staffOverflow, flowOverflow, newQuota, newCapacity);
        }
    }

    // ============================ 私有辅助 ============================

    private void assertNoOverload(Long scenarioId, Long justClosedAreaId, Map<Long, StoreArea> areaMap) {
        List<StaffAllocation> all = allocationRepository.findByScenarioIdAndIsOptimized(scenarioId, false);
        for (StaffAllocation a : all) {
            StoreArea area = areaMap.get(a.getAreaId());
            if (area == null) {
                continue;
            }
            if (a.getAllocatedStaff() > area.getStaffQuota()
                    || a.getAllocatedFlow() > area.getMaxCapacity()) {
                throw new IllegalStateException("回灌后区域「" + area.getAreaName()
                        + "」超额（人员" + a.getAllocatedStaff() + "/" + area.getStaffQuota()
                        + " 客流" + a.getAllocatedFlow() + "/" + area.getMaxCapacity() + "），事务回滚");
            }
            if (a.getAreaId().equals(justClosedAreaId)
                    && (a.getAllocatedStaff() != 0 || a.getAllocatedFlow() != 0)) {
                throw new IllegalStateException("被封区域未清 0，事务回滚");
            }
        }
    }

    private ClosureFailureDTO buildFailure(Long scenarioId, StoreArea closedArea,
                                           int evacuatedStaff, int evacuatedFlow,
                                           int totalRemainingStaff, int totalRemainingFlow,
                                           int shortStaff, int shortFlow, boolean noOpenArea,
                                           List<Receiver> receivers,
                                           Map<Long, Set<Long>> occupiedByReceiver) {
        ClosureFailureDTO dto = new ClosureFailureDTO();
        dto.setScenarioId(scenarioId);
        dto.setClosedAreaId(closedArea.getId());
        dto.setClosedAreaName(closedArea.getAreaName());
        dto.setEvacuatedStaff(evacuatedStaff);
        dto.setEvacuatedFlow(evacuatedFlow);
        dto.setTotalRemainingStaff(totalRemainingStaff);
        dto.setTotalRemainingFlow(totalRemainingFlow);
        dto.setShortStaff(shortStaff);
        dto.setShortFlow(shortFlow);
        dto.setNoOpenArea(noOpenArea);

        List<ClosureFailureDTO.ReceiverCapacityView> views = new ArrayList<>();
        for (Receiver r : receivers) {
            ClosureFailureDTO.ReceiverCapacityView v = new ClosureFailureDTO.ReceiverCapacityView();
            v.setAreaId(r.area.getId());
            v.setAreaName(r.area.getAreaName());
            v.setStaffBefore(r.staffBefore);
            v.setFlowBefore(r.flowBefore);
            v.setStaffQuota(r.area.getStaffQuota());
            v.setMaxCapacity(r.area.getMaxCapacity());
            v.setRemainingStaff(r.remainingStaff);
            v.setRemainingFlow(r.remainingFlow);
            Set<Long> occ = occupiedByReceiver == null ? null
                    : occupiedByReceiver.getOrDefault(r.area.getId(), new TreeSet<>());
            v.setOccupiedByClosureIds(occ == null ? List.of() : new ArrayList<>(occ));
            views.add(v);
        }
        dto.setReceivers(views);
        dto.setMessage(buildFailureMessage(closedArea, evacuatedStaff, evacuatedFlow,
                totalRemainingStaff, totalRemainingFlow, shortStaff, shortFlow, noOpenArea, receivers));
        return dto;
    }

    private String buildFailureMessage(StoreArea closedArea, int evacuatedStaff, int evacuatedFlow,
                                       int totalRemainingStaff, int totalRemainingFlow,
                                       int shortStaff, int shortFlow, boolean noOpenArea,
                                       List<Receiver> receivers) {
        StringBuilder sb = new StringBuilder("回灌落不成账，已按安全岗要求拒绝关区；被封区域「")
                .append(closedArea.getAreaName()).append("」保持可营业，旧分配未动。")
                .append(" 本次需疏散：人员").append(evacuatedStaff).append("、客流").append(evacuatedFlow).append("。");
        if (noOpenArea) {
            sb.append(" 当前没有任何仍开放的承接区域。");
            return sb.toString();
        }
        sb.append(" 开放区域剩余编制合计").append(totalRemainingStaff)
                .append("、剩余容量合计").append(totalRemainingFlow).append("；");
        List<String> gaps = new ArrayList<>();
        if (shortStaff > 0) {
            gaps.add("人员编制还差" + shortStaff);
        }
        if (shortFlow > 0) {
            gaps.add("容量还差" + shortFlow);
        }
        if (!gaps.isEmpty()) {
            sb.append("整体").append(String.join("、", gaps)).append("。");
        }
        sb.append(" 各承接区域：");
        List<String> parts = new ArrayList<>();
        for (Receiver r : receivers) {
            String p = "「" + r.area.getAreaName() + "」剩编制" + r.remainingStaff
                    + "/容量" + r.remainingFlow;
            if (r.staffBefore > 0 || r.flowBefore > 0) {
                p += "（已占用人员" + r.staffBefore + "、客流" + r.flowBefore
                        + "，含其他封区单回灌占掉的剩余量）";
            }
            parts.add(p);
        }
        sb.append(String.join("；", parts)).append("。");
        return sb.toString();
    }

    private AreaClosure getClosureOrThrow(Long id) {
        return closureRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("封区单不存在: " + id));
    }

    private Map<Long, String> scenarioNames(Set<Long> ids) {
        Map<Long, String> map = new HashMap<>();
        if (ids == null || ids.isEmpty()) {
            return map;
        }
        for (FlowScenario s : scenarioRepository.findAllById(ids)) {
            map.put(s.getId(), s.getScenarioName());
        }
        return map;
    }

    private AreaClosureDTO toDTO(AreaClosure c, String scenarioName) {
        List<ClosureInjection> ledger = c.getStatus() == ClosureStatus.EFFECTIVE
                || c.getStatus() == ClosureStatus.QUOTA_CONFLICT
                || c.getStatus() == ClosureStatus.REOPENED
                || c.getStatus() == ClosureStatus.VOIDED
                ? injectionRepository.findByClosureIdOrderByIdAsc(c.getId())
                : List.of();
        List<InjectionDTO> injectionDTOs = ledger.stream().map(this::toInjectionDTO)
                .collect(Collectors.toList());
        ClosureFailureDTO failure = c.getStatus() == ClosureStatus.FAILED
                ? readJson(c.getFailReceipt(), ClosureFailureDTO.class) : null;
        QuotaConflictDTO qc = c.getStatus() == ClosureStatus.QUOTA_CONFLICT
                ? readJson(c.getQuotaConflict(), QuotaConflictDTO.class) : null;
        return toDTO(c, scenarioName, injectionDTOs, failure, qc);
    }

    private AreaClosureDTO toDTO(AreaClosure c, FlowScenario scenario,
                                 List<ClosureInjection> injections,
                                 ClosureFailureDTO failure, QuotaConflictDTO qc) {
        return toDTO(c, scenario != null ? scenario.getScenarioName() : null,
                injections.stream().map(this::toInjectionDTO).collect(Collectors.toList()),
                failure, qc);
    }

    private AreaClosureDTO toDTO(AreaClosure c, String scenarioName,
                                 List<InjectionDTO> injections,
                                 ClosureFailureDTO failure, QuotaConflictDTO qc) {
        AreaClosureDTO dto = new AreaClosureDTO();
        dto.setId(c.getId());
        dto.setScenarioId(c.getScenarioId());
        dto.setScenarioName(scenarioName);
        dto.setAreaId(c.getAreaId());
        dto.setAreaName(c.getAreaName());
        dto.setReason(c.getReason());
        dto.setStatus(c.getStatus().name());
        dto.setStatusText(statusText(c.getStatus()));
        dto.setLive(c.getStatus().isLive());
        dto.setUnsealBlocked(c.getStatus() == ClosureStatus.QUOTA_CONFLICT);
        dto.setEvacuatedStaff(c.getEvacuatedStaff());
        dto.setEvacuatedFlow(c.getEvacuatedFlow());
        dto.setInjectedStaff(c.getInjectedStaff());
        dto.setInjectedFlow(c.getInjectedFlow());
        dto.setFailure(failure);
        dto.setQuotaConflict(qc);
        dto.setInjections(injections);
        dto.setTerminalNote(c.getTerminalNote());
        dto.setCreatedAt(c.getCreatedAt());
        dto.setUpdatedAt(c.getUpdatedAt());
        return dto;
    }

    private InjectionDTO toInjectionDTO(ClosureInjection in) {
        InjectionDTO dto = new InjectionDTO();
        dto.setId(in.getId());
        dto.setClosureId(in.getClosureId());
        dto.setScenarioId(in.getScenarioId());
        dto.setReceiverAreaId(in.getReceiverAreaId());
        dto.setReceiverAreaName(in.getReceiverAreaName());
        dto.setInjectedStaff(in.getInjectedStaff());
        dto.setInjectedFlow(in.getInjectedFlow());
        dto.setStaffBefore(in.getStaffBefore());
        dto.setFlowBefore(in.getFlowBefore());
        dto.setRemainingStaffAtTime(in.getRemainingStaffAtTime());
        dto.setRemainingFlowAtTime(in.getRemainingFlowAtTime());
        return dto;
    }

    private String statusText(ClosureStatus s) {
        switch (s) {
            case EFFECTIVE: return "封区生效中";
            case FAILED: return "封区失败·区域仍可营业";
            case QUOTA_CONFLICT: return "额度冲突·禁止解封";
            case REOPENED: return "已解封";
            case VOIDED: return "已作废·回灌保留";
            default: return s.name();
        }
    }

    private String writeJson(Object o) {
        if (o == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(o);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("序列化封区明细失败", e);
        }
    }

    private <T> T readJson(String json, Class<T> type) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(json, type);
        } catch (JsonProcessingException e) {
            log.warn("反序列化封区明细失败: {}", json, e);
            return null;
        }
    }

    /** 回灌试算用的承接区域视图。 */
    private static final class Receiver {
        final StoreArea area;
        final int staffBefore;
        final int flowBefore;
        final int remainingStaff;
        final int remainingFlow;

        Receiver(StoreArea area, int staffBefore, int flowBefore,
                 int remainingStaff, int remainingFlow) {
            this.area = area;
            this.staffBefore = staffBefore;
            this.flowBefore = flowBefore;
            this.remainingStaff = remainingStaff;
            this.remainingFlow = remainingFlow;
        }
    }
}
