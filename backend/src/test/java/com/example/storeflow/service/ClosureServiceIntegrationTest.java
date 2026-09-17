package com.example.storeflow.service;

import com.example.storeflow.dto.*;
import com.example.storeflow.entity.*;
import com.example.storeflow.exception.BusinessConflictException;
import com.example.storeflow.repository.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;
import com.example.storeflow.TestRedisConfig;

import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 临时封区回灌的端到端集成测试（H2）。
 *
 * <p>覆盖：原子成功拆分且不超额 / 承接不下整单失败旧账不动 / 同场景并发只成功一单 /
 * 写回守卫 / 额度冲突禁止解封只能作废 / 作废不撤回 / 解封撤回 / 单据持久化失败不变成功。
 */
@SpringBootTest(classes = {com.example.storeflow.StoreFlowApplication.class, TestRedisConfig.class})
@Transactional
class ClosureServiceIntegrationTest {

    @Autowired private ClosureService closureService;
    @Autowired private StoreAreaService areaService;
    @Autowired private StoreAreaRepository areaRepository;
    @Autowired private FlowScenarioRepository scenarioRepository;
    @Autowired private StaffAllocationRepository allocationRepository;
    @Autowired private AreaClosureRepository closureRepository;
    @Autowired private ClosureInjectionRepository injectionRepository;
    @Autowired private org.springframework.transaction.PlatformTransactionManager txManager;

    private StoreArea area(String name, int cap, int quota) {
        StoreArea a = new StoreArea();
        a.setAreaName(name + "-" + UUID.randomUUID());
        a.setMaxCapacity(cap);
        a.setStaffQuota(quota);
        a.setRiskFlag(false);
        a.setRiskLevel(0);
        return areaRepository.save(a);
    }

    private FlowScenario scenario(String name) {
        FlowScenario s = new FlowScenario();
        s.setScenarioName(name + "-" + UUID.randomUUID());
        s.setFestivalName("测试节");
        s.setEstimatedTotalFlow(1000);
        return scenarioRepository.save(s);
    }

    private void alloc(long scenarioId, long areaId, int staff, int flow) {
        StaffAllocation a = new StaffAllocation();
        a.setScenarioId(scenarioId);
        a.setAreaId(areaId);
        a.setAllocatedStaff(staff);
        a.setAllocatedFlow(flow);
        a.setIsOptimized(false);
        allocationRepository.save(a);
    }

    private Map<Long, StaffAllocation> baseMap(long scenarioId) {
        return allocationRepository.findByScenarioIdAndIsOptimized(scenarioId, false).stream()
                .collect(Collectors.toMap(StaffAllocation::getAreaId, a -> a));
    }

    private void wipeAll(org.springframework.transaction.support.TransactionTemplate tx) {
        tx.executeWithoutResult(status -> {
            injectionRepository.deleteAll();
            closureRepository.deleteAll();
            allocationRepository.deleteAll();
            areaRepository.deleteAll();
            scenarioRepository.deleteAll();
        });
    }

    private ClosureSubmitRequest req(long scenarioId, long areaId, String reason) {
        ClosureSubmitRequest r = new ClosureSubmitRequest();
        r.setScenarioId(scenarioId);
        r.setAreaId(areaId);
        r.setReason(reason);
        return r;
    }

    // ---------- 1. 成功：清 0 + 按剩余额度拆分到开放区域，且不超额 ----------

    @Test
    void successfulClosureZeroesClosedAreaAndSplitsWithoutOverload() {
        StoreArea closed = area("故障区", 100, 10);
        StoreArea r1 = area("承接A", 100, 10);
        StoreArea r2 = area("承接B", 100, 10);
        FlowScenario s = scenario("封区成功");
        // 被封区 50 人/流 配置为：人员 8、客流 50；承接区预先各占 5 人员/50 客流 => 各剩 5/50
        alloc(s.getId(), closed.getId(), 8, 50);
        alloc(s.getId(), r1.getId(), 5, 50);
        alloc(s.getId(), r2.getId(), 5, 50);

        AreaClosureDTO dto = closureService.submitClosure(
                req(s.getId(), closed.getId(), "设备故障"));

        assertEquals("EFFECTIVE", dto.getStatus());
        assertTrue(dto.getLive());
        assertEquals(8, dto.getEvacuatedStaff());
        assertEquals(50, dto.getEvacuatedFlow());
        assertEquals(8, dto.getInjectedStaff());
        assertEquals(50, dto.getInjectedFlow());

        Map<Long, StaffAllocation> base = baseMap(s.getId());
        // 被封区域清成零
        assertEquals(0, base.get(closed.getId()).getAllocatedStaff());
        assertEquals(0, base.get(closed.getId()).getAllocatedFlow());

        // 至少一块承接区域，且任何承接区域不超容量/编制
        List<InjectionDTO> inj = dto.getInjections();
        assertFalse(inj.isEmpty());
        Set<Long> receivers = inj.stream().map(InjectionDTO::getReceiverAreaId).collect(Collectors.toSet());
        assertTrue(receivers.contains(r1.getId()) || receivers.contains(r2.getId()));
        int gotStaff = 0, gotFlow = 0;
        for (InjectionDTO in : inj) {
            StoreArea area = areaRepository.findById(in.getReceiverAreaId()).orElseThrow();
            StaffAllocation ba = base.get(in.getReceiverAreaId());
            assertTrue(ba.getAllocatedStaff() <= area.getStaffQuota(), "承接区域超编制");
            assertTrue(ba.getAllocatedFlow() <= area.getMaxCapacity(), "承接区域超容量");
            // 台账记录的落账前量应与“当前量 - 本次注入量”一致，且当时前量确为 50/5
            assertEquals(in.getFlowBefore(), ba.getAllocatedFlow() - in.getInjectedFlow());
            assertEquals(in.getStaffBefore(), ba.getAllocatedStaff() - in.getInjectedStaff());
            assertEquals(50, in.getFlowBefore().intValue(), "承接区落账前客流应为 50");
            assertEquals(5, in.getStaffBefore().intValue(), "承接区落账前人员应为 5");
            gotStaff += in.getInjectedStaff();
            gotFlow += in.getInjectedFlow();
        }
        assertEquals(8, gotStaff);
        assertEquals(50, gotFlow);
    }

    // ---------- 2. 失败：开放区域吃不下，整单失败、被封区域保持可营业、旧账一行不动 ----------

    @Test
    void failedClosureKeepsAreaOpenAndAllocationsUntouchedWithReceipt() {
        StoreArea closed = area("顶满区", 100, 10);
        StoreArea r1 = area("饱和A", 100, 10);
        FlowScenario s = scenario("封区失败");
        alloc(s.getId(), closed.getId(), 9, 90);
        alloc(s.getId(), r1.getId(), 9, 95); // 只剩编制1、容量5

        AreaClosureDTO dto = closureService.submitClosure(
                req(s.getId(), closed.getId(), "客流顶满"));

        assertEquals("FAILED", dto.getStatus());
        assertFalse(dto.getLive());
        assertNotNull(dto.getFailure());
        // 回执点名卡在哪块承接区域、还差多少
        ClosureFailureDTO f = dto.getFailure();
        assertTrue(f.getShortStaff() > 0 || f.getShortFlow() > 0);
        assertEquals(9, f.getEvacuatedStaff());
        assertEquals(90, f.getEvacuatedFlow());
        assertFalse(f.getReceivers().isEmpty());
        assertNotNull(f.getMessage());
        assertTrue(f.getMessage().contains("回灌落不成账"));

        // 旧分配一行不动：被封区域仍是 9/90，保持可营业
        Map<Long, StaffAllocation> base = baseMap(s.getId());
        assertEquals(9, base.get(closed.getId()).getAllocatedStaff());
        assertEquals(90, base.get(closed.getId()).getAllocatedFlow());
        assertEquals(9, base.get(r1.getId()).getAllocatedStaff());
        assertEquals(95, base.get(r1.getId()).getAllocatedFlow());
        // 失败不产生台账
        assertTrue(injectionRepository.findAll().isEmpty());
    }

    // ---------- 3. 没有任何开放区域且有疏散量：失败 ----------

    @Test
    void closingTheOnlyAreaWithLoadFails() {
        StoreArea only = area("唯一区", 100, 10);
        FlowScenario s = scenario("单区");
        alloc(s.getId(), only.getId(), 5, 50);

        AreaClosureDTO dto = closureService.submitClosure(req(s.getId(), only.getId(), "故障"));
        assertEquals("FAILED", dto.getStatus());
        assertTrue(dto.getFailure().getNoOpenArea());
        assertEquals(5, baseMap(s.getId()).get(only.getId()).getAllocatedStaff());
    }

    // ---------- 4. 并发：同场景封两块不同区域、都抢同一仅剩开放区域，只成功一单 ----------

    @Test
    @org.springframework.transaction.annotation.Transactional(propagation =
            org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    void concurrentClosuresTargetingSameLastOpenAreaOnlyOneCommits() throws Exception {
        // 该方法不能套在外层测试事务里：工作线程的独立事务必须看到已提交的建数数据。
        // 它真实提交数据，所以开始前手动清库、结束后也清库，杜绝与其它用例的顺序耦合。
        org.springframework.transaction.support.TransactionTemplate tx =
                new org.springframework.transaction.support.TransactionTemplate(txManager);
        wipeAll(tx);
        Long[] ids = tx.execute(status -> {
            // 容量/编制：A、B、D 恰好 60/6；receiver 100/10
            StoreArea aa = area("并发封区A", 60, 6);
            StoreArea bb = area("并发封区B", 60, 6);
            StoreArea dd = area("前置已封D", 60, 6);
            StoreArea rr = area("唯一承接", 100, 10);
            FlowScenario ss = scenario("并发场景");
            // A、B 各 60 流/6 人（占满自身额度：余 0，且待疏散 60/6）；
            // D 0 负荷（仅用于把它从开放集封掉，不向 receiver 注入）；receiver 40/4（剩 60/6，恰好够一单）
            alloc(ss.getId(), aa.getId(), 6, 60);
            alloc(ss.getId(), bb.getId(), 6, 60);
            alloc(ss.getId(), dd.getId(), 0, 0);
            alloc(ss.getId(), rr.getId(), 4, 40);
            return new Long[]{ss.getId(), aa.getId(), bb.getId(), dd.getId(), rr.getId()};
        });
        long scenarioId = ids[0];
        long aId = ids[1];
        long bId = ids[2];
        long dId = ids[3];
        long receiverId = ids[4];

        // 前置：封死 D（0/0，清空但不产生回灌）。这样此后任何封区单的开放候选 = {A?, B?, receiver}。
        AreaClosureDTO pre = closureService.submitClosure(req(scenarioId, dId, "前置封区"));
        assertEquals("EFFECTIVE", pre.getStatus());
        StaffAllocation preReceiver = allocationRepository.findBaseAllocation(scenarioId, receiverId);
        assertEquals(40, preReceiver.getAllocatedFlow()); // 未被前置单改动
        assertEquals(4, preReceiver.getAllocatedStaff());

        // 并发封 A、B（各待疏散 60/6）。每个任务的开放候选：对方区域（占满，余 0）+ receiver（剩 60/6）。
        // 先到单把 receiver 剩余 60/6 吃光 -> receiver 100/10；后到单 receiver 余 0、对方也余 0，整单失败。
        int threads = 2;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<AreaClosureDTO>> futures = new ArrayList<>();
        long[] target = {aId, bId};
        for (int i = 0; i < threads; i++) {
            long areaId = target[i];
            futures.add(pool.submit(() -> {
                start.await();
                return closureService.submitClosure(req(scenarioId, areaId, "并发封区"));
            }));
        }
        start.countDown();
        List<AreaClosureDTO> results = new ArrayList<>();
        for (Future<AreaClosureDTO> fu : futures) {
            results.add(fu.get(30, TimeUnit.SECONDS));
        }
        pool.shutdown();

        long success = results.stream().filter(d -> "EFFECTIVE".equals(d.getStatus())).count();
        long failed = results.stream().filter(d -> "FAILED".equals(d.getStatus())).count();
        assertEquals(1, success, "只允许一单成功落账");
        assertEquals(1, failed, "另一单必须失败");

        AreaClosureDTO failDto = results.stream().filter(d -> "FAILED".equals(d.getStatus())).findFirst().orElseThrow();
        AreaClosureDTO succDto = results.stream().filter(d -> "EFFECTIVE".equals(d.getStatus())).findFirst().orElseThrow();
        assertNotNull(failDto.getFailure());

        // 回执点名唯一承接区 receiver，且其剩余量被成功的那一单（与前置单）占用
        ClosureFailureDTO.ReceiverCapacityView rv =
                failDto.getFailure().getReceivers().stream()
                        .filter(v -> v.getAreaId().equals(receiverId)).findFirst().orElseThrow();
        assertTrue(rv.getOccupiedByClosureIds().contains(succDto.getId()),
                "回执应指明 receiver 已被成功封区单 #" + succDto.getId() + " 占用，实际: "
                        + rv.getOccupiedByClosureIds());
        assertEquals(0, rv.getRemainingFlow(), "失败单看到的 receiver 剩余容量应为 0");
        assertEquals(0, rv.getRemainingStaff(), "失败单看到的 receiver 剩余编制应为 0");
        assertTrue(failDto.getFailure().getMessage().contains("回灌落不成账"));

        // 成功单回灌全部落在 receiver
        assertTrue(succDto.getInjections().stream()
                .allMatch(i -> i.getReceiverAreaId().equals(receiverId)));

        // 承接区不超额；场景总量守恒
        StaffAllocation receiverBase = allocationRepository.findBaseAllocation(scenarioId, receiverId);
        assertEquals(100, receiverBase.getAllocatedFlow());
        assertEquals(10, receiverBase.getAllocatedStaff());
        Map<Long, StaffAllocation> base = baseMap(scenarioId);
        int totalFlow = base.values().stream().mapToInt(StaffAllocation::getAllocatedFlow).sum();
        int totalStaff = base.values().stream().mapToInt(StaffAllocation::getAllocatedStaff).sum();
        // A0(成功封) + B60(失败保持) + D0(前置封) + receiver100 = 160 流；人员 0+6+0+10 = 16
        assertEquals(160, totalFlow);
        assertEquals(16, totalStaff);

        // 清理本方法真实提交的数据，避免污染后续用例
        wipeAll(tx);
    }

    // ---------- 5. 关掉页面再打开：成功仍生效，失败不会自己变成功 ----------

    @Test
    void persistenceKeepsFailedFailedAndEffectiveEffective() {
        // s1：closed 待疏散、ok 可承接 => 成功
        StoreArea closed = area("持久区", 100, 10);
        StoreArea ok = area("充足承接", 100, 10);
        FlowScenario s1 = scenario("会成功");
        alloc(s1.getId(), closed.getId(), 4, 40);
        alloc(s1.getId(), ok.getId(), 1, 10);

        // s2：独立的两个区。closed2 待疏散 9/90；bad 已 9/95（剩 1/5）吃不下 => 失败
        StoreArea closed2 = area("持久区2", 100, 10);
        StoreArea bad = area("不足承接", 100, 10);
        FlowScenario s2 = scenario("会失败");
        alloc(s2.getId(), closed2.getId(), 9, 90);
        alloc(s2.getId(), bad.getId(), 9, 95);
        // s2 视角下 closed、ok 也存在（档案共享），把它们在 s2 占满，避免成为兜底承接区
        alloc(s2.getId(), closed.getId(), 10, 100);
        alloc(s2.getId(), ok.getId(), 10, 100);

        AreaClosureDTO okDto = closureService.submitClosure(req(s1.getId(), closed.getId(), "故障"));
        AreaClosureDTO badDto = closureService.submitClosure(req(s2.getId(), closed2.getId(), "故障"));
        assertEquals("EFFECTIVE", okDto.getStatus());
        assertEquals("FAILED", badDto.getStatus());

        // 重新从库里查（模拟关掉页面再打开）
        AreaClosureDTO reOk = closureService.getById(okDto.getId());
        AreaClosureDTO reBad = closureService.getById(badDto.getId());
        assertTrue(reOk.getLive());
        assertEquals("EFFECTIVE", reOk.getStatus());
        assertFalse(reBad.getLive());
        assertEquals("FAILED", reBad.getStatus());
        assertNotNull(reBad.getFailure());
        assertTrue(reBad.getInjections().isEmpty(), "失败单无回灌台账");
    }

    // ---------- 6. 生效期内禁止手工把人员/客流写回被封区域 ----------

    @Test
    void cannotManuallyWriteBackToLiveClosedArea() {
        StoreArea closed = area("写回区", 100, 10);
        StoreArea r = area("写回承接", 100, 10);
        FlowScenario s = scenario("写回守卫");
        alloc(s.getId(), closed.getId(), 4, 40);
        alloc(s.getId(), r.getId(), 1, 10);

        closureService.submitClosure(req(s.getId(), closed.getId(), "故障"));

        BusinessConflictException ex = assertThrows(BusinessConflictException.class,
                () -> closureService.assertCanWriteAllocation(s.getId(), closed.getId()));
        assertEquals("AREA_CLOSED", ex.getCode());

        // 开放区域仍可写
        assertDoesNotThrow(() -> closureService.assertCanWriteAllocation(s.getId(), r.getId()));
    }

    // ---------- 7. 承接区域额度改小：封区单进入额度冲突、禁止解封、只能作废 ----------

    @Test
    void shrinkingReceiverQuotaMarksConflictBlocksUnsealAndVoidRestoresAreaWithoutWithdrawing() {
        StoreArea closed = area("冲突封区", 100, 10);
        StoreArea receiver = area("冲突承接", 100, 10);
        FlowScenario s = scenario("额度冲突");
        alloc(s.getId(), closed.getId(), 5, 50);
        alloc(s.getId(), receiver.getId(), 0, 0);

        AreaClosureDTO ok = closureService.submitClosure(req(s.getId(), closed.getId(), "故障"));
        assertEquals("EFFECTIVE", ok.getStatus());
        // 回灌全部落在唯一承接区：5 人员 / 50 客流
        InjectionDTO in = ok.getInjections().stream()
                .filter(i -> i.getReceiverAreaId().equals(receiver.getId())).findFirst().orElseThrow();
        assertEquals(5, in.getInjectedStaff());
        assertEquals(50, in.getInjectedFlow());

        // 承接区域容量从 100 改小到 40（< 回灌 50）
        AreaConfigDTO smaller = new AreaConfigDTO();
        smaller.setAreaName(areaRepository.findById(receiver.getId()).orElseThrow().getAreaName());
        smaller.setMaxCapacity(40);
        smaller.setStaffQuota(10); // 编制不变，仅容量冲突
        smaller.setDescription("");
        areaService.updateArea(receiver.getId(), smaller);

        AreaClosureDTO conflicted = closureService.getById(ok.getId());
        assertEquals("QUOTA_CONFLICT", conflicted.getStatus());
        assertTrue(conflicted.getUnsealBlocked());
        assertNotNull(conflicted.getQuotaConflict());
        assertEquals(Integer.valueOf(10), conflicted.getQuotaConflict().getConflicts().get(0).getFlowOverflow());

        // 解封被拒绝
        BusinessConflictException ex = assertThrows(BusinessConflictException.class,
                () -> closureService.reopenClosure(ok.getId(), "尝试解封"));
        assertEquals("UNSEAL_BLOCKED_QUOTA_CONFLICT", ex.getCode());

        // 回灌台账数字没有偷偷改
        ClosureInjection ledger = injectionRepository.findByClosureIdOrderByIdAsc(ok.getId()).get(0);
        assertEquals(50, ledger.getInjectedFlow());

        // 值班长手工作废：区域恢复可营业，但回灌不撤回
        AreaClosureDTO voided = closureService.voidClosure(ok.getId(), "值班长手工作废");
        assertEquals("VOIDED", voided.getStatus());
        assertFalse(voided.getLive());
        StaffAllocation receiverAlloc = allocationRepository.findBaseAllocation(s.getId(), receiver.getId());
        assertEquals(50, receiverAlloc.getAllocatedFlow(), "作废后回灌客流不得撤回");
        assertEquals(5, receiverAlloc.getAllocatedStaff(), "作废后回灌人员不得撤回");
        // 被封区域不再封锁（可手工写回/营业）
        assertDoesNotThrow(() -> closureService.assertCanWriteAllocation(s.getId(), closed.getId()));
        // 台账仍在
        assertFalse(injectionRepository.findByClosureIdOrderByIdAsc(ok.getId()).isEmpty());
    }

    // ---------- 8. 正常解封：撤回回灌、被封区域恢复封区前快照 ----------

    @Test
    void reopenWithdrawsInjectionAndRestoresClosedArea() {
        StoreArea closed = area("解封区", 100, 10);
        StoreArea r = area("解封承接", 100, 10);
        FlowScenario s = scenario("解封场景");
        alloc(s.getId(), closed.getId(), 5, 50);
        alloc(s.getId(), r.getId(), 1, 10);

        AreaClosureDTO ok = closureService.submitClosure(req(s.getId(), closed.getId(), "故障"));
        Map<Long, StaffAllocation> during = baseMap(s.getId());
        assertEquals(0, during.get(closed.getId()).getAllocatedFlow());

        AreaClosureDTO reopened = closureService.reopenClosure(ok.getId(), "恢复营业");
        assertEquals("REOPENED", reopened.getStatus());
        assertFalse(reopened.getLive());

        Map<Long, StaffAllocation> after = baseMap(s.getId());
        assertEquals(50, after.get(closed.getId()).getAllocatedFlow());
        assertEquals(5, after.get(closed.getId()).getAllocatedStaff());
        assertEquals(10, after.get(r.getId()).getAllocatedFlow(), "承接区撤回回灌回到 10");
        assertEquals(1, after.get(r.getId()).getAllocatedStaff());
    }

    // ---------- 9. 重复封同一对（场景，区域）被拒绝 ----------

    @Test
    void doubleClosingSameAreaInSameScenarioRejected() {
        StoreArea closed = area("重复封区", 100, 10);
        StoreArea r = area("重复承接", 100, 10);
        FlowScenario s = scenario("重复");
        alloc(s.getId(), closed.getId(), 2, 20);
        alloc(s.getId(), r.getId(), 1, 10);

        closureService.submitClosure(req(s.getId(), closed.getId(), "故障"));
        BusinessConflictException ex = assertThrows(BusinessConflictException.class,
                () -> closureService.submitClosure(req(s.getId(), closed.getId(), "再来一次")));
        assertEquals("AREA_ALREADY_CLOSED", ex.getCode());
    }

    // ---------- 10. 封控区域不进入优化推演候选（既非源也非目标） ----------

    @Test
    void liveClosedAreasExcludedFromOptimizationTargets() {
        StoreArea closed = area("优化封区", 100, 10);
        StoreArea r = area("优化承接", 100, 10);
        FlowScenario s = scenario("优化封控");
        alloc(s.getId(), closed.getId(), 5, 50);
        alloc(s.getId(), r.getId(), 1, 10);

        closureService.submitClosure(req(s.getId(), closed.getId(), "故障"));
        Set<Long> closedIds = closureService.liveClosedAreaIds(s.getId());
        assertTrue(closedIds.contains(closed.getId()));
        assertFalse(closedIds.contains(r.getId()));
    }
}
