package com.example.storeflow.service;

import com.example.storeflow.TestRedisConfig;
import com.example.storeflow.dto.AreaClosureDTO;
import com.example.storeflow.dto.AreaConfigDTO;
import com.example.storeflow.dto.AreaLoadDTO;
import com.example.storeflow.dto.ClosureSubmitRequest;
import com.example.storeflow.dto.OptimizationBatchDTO;
import com.example.storeflow.dto.OptimizationBatchItemDTO;
import com.example.storeflow.dto.OptimizationResultDTO;
import com.example.storeflow.entity.FlowScenario;
import com.example.storeflow.entity.StaffAllocation;
import com.example.storeflow.entity.StoreArea;
import com.example.storeflow.exception.BusinessConflictException;
import com.example.storeflow.repository.AreaClosureRepository;
import com.example.storeflow.repository.ClosureInjectionRepository;
import com.example.storeflow.repository.FlowScenarioRepository;
import com.example.storeflow.repository.OptimizationBatchItemRepository;
import com.example.storeflow.repository.OptimizationBatchRepository;
import com.example.storeflow.repository.StaffAllocationRepository;
import com.example.storeflow.repository.StoreAreaRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 优化落地批次会签的端到端集成测试（H2）。
 *
 * <p>覆盖：推演冻批次且场景不动 / 测算确认后签收前仍是旧方案 / 签收与切场景一笔事务 /
 * 未确认不能签收 / 确认后新一轮推演作废旧批次（失败说明点名批次与轮次）/
 * 冻结当时档案 / 重复签收拒绝 / 并发签收只落地一次 / 落地后重查仍是优化后方案 /
 * 封区生效区域禁止落地写回。
 */
@SpringBootTest(classes = {com.example.storeflow.StoreFlowApplication.class, TestRedisConfig.class})
@Transactional
class OptimizationBatchServiceIntegrationTest {

    @Autowired private FlowScenarioService flowScenarioService;
    @Autowired private OptimizationBatchService batchService;
    @Autowired private ClosureService closureService;
    @Autowired private StoreAreaService areaService;
    @Autowired private StoreAreaRepository areaRepository;
    @Autowired private FlowScenarioRepository scenarioRepository;
    @Autowired private StaffAllocationRepository allocationRepository;
    @Autowired private OptimizationBatchRepository batchRepository;
    @Autowired private OptimizationBatchItemRepository batchItemRepository;
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

    private OptimizationBatchItemDTO itemOf(OptimizationBatchDTO batch, long areaId) {
        return batch.getItems().stream()
                .filter(i -> i.getAreaId().equals(areaId)).findFirst().orElseThrow();
    }

    // ---------- 1. 推演冻成批次：冻结当时档案，场景分配一行不动 ----------

    @Test
    void runFreezesBatchWithRunTimeArchiveAndLeavesScenarioUntouched() {
        StoreArea a = area("热区", 100, 10);
        StoreArea b = area("冷区", 100, 10);
        FlowScenario s = scenario("冻结批次");
        alloc(s.getId(), a.getId(), 8, 90);
        alloc(s.getId(), b.getId(), 2, 10);

        OptimizationResultDTO result = flowScenarioService.optimize(s.getId());

        assertEquals(1, result.getRunSeq());
        assertNotNull(result.getBatchId());

        OptimizationBatchDTO batch = batchService.getById(result.getBatchId());
        assertEquals("PENDING_CONFIRM", batch.getStatus());
        assertEquals(1, batch.getRunSeq());
        assertEquals(2, batch.getItems().size());

        // 批次冻住推演当时的核定容量、编制配额、优化前分配
        OptimizationBatchItemDTO itemA = itemOf(batch, a.getId());
        assertEquals(100, itemA.getMaxCapacity());
        assertEquals(10, itemA.getStaffQuota());
        assertEquals(8, itemA.getBeforeStaff());
        assertEquals(90, itemA.getBeforeFlow());
        OptimizationBatchItemDTO itemB = itemOf(batch, b.getId());
        assertEquals(2, itemB.getBeforeStaff());
        assertEquals(10, itemB.getBeforeFlow());

        // 优化后分配与推演结果一致，且确实发生了调配（否则本用例失去意义）
        Map<Long, com.example.storeflow.dto.AllocationDTO> afterByArea = result.getAfterAllocations().stream()
                .collect(Collectors.toMap(com.example.storeflow.dto.AllocationDTO::getAreaId, x -> x));
        assertEquals(afterByArea.get(a.getId()).getAllocatedStaff(), itemA.getAfterStaff());
        assertEquals(afterByArea.get(a.getId()).getAllocatedFlow(), itemA.getAfterFlow());
        assertNotEquals(90, itemA.getAfterFlow().intValue(), "推演应产生不同的优化后方案");

        // 场景当前分配仍是旧方案；轮次已记为第 1 轮，尚未落地任何方案
        Map<Long, StaffAllocation> base = baseMap(s.getId());
        assertEquals(8, base.get(a.getId()).getAllocatedStaff());
        assertEquals(90, base.get(a.getId()).getAllocatedFlow());
        assertEquals(2, base.get(b.getId()).getAllocatedStaff());
        assertEquals(10, base.get(b.getId()).getAllocatedFlow());
        FlowScenario reloaded = scenarioRepository.findById(s.getId()).orElseThrow();
        assertEquals(1, reloaded.getCurrentRunSeq());
        assertNull(reloaded.getAppliedRunSeq());
    }

    // ---------- 2. 会签主流程：确认不动场景，签收与切场景一笔事务 ----------

    @Test
    void confirmKeepsOldPlanAndSignSwitchesScenarioAtomically() {
        StoreArea a = area("主热区", 100, 10);
        StoreArea b = area("主冷区", 100, 10);
        FlowScenario s = scenario("会签主流程");
        alloc(s.getId(), a.getId(), 8, 90);
        alloc(s.getId(), b.getId(), 2, 10);

        OptimizationResultDTO result = flowScenarioService.optimize(s.getId());
        Long batchId = result.getBatchId();

        // 测算岗确认：批次推进，场景分配必须仍是旧方案
        OptimizationBatchDTO confirmed = batchService.confirm(batchId, "测算岗张三");
        assertEquals("CONFIRMED", confirmed.getStatus());
        assertEquals("测算岗张三", confirmed.getConfirmedBy());
        assertNotNull(confirmed.getConfirmedAt());
        Map<Long, StaffAllocation> afterConfirm = baseMap(s.getId());
        assertEquals(8, afterConfirm.get(a.getId()).getAllocatedStaff(), "确认后签收前场景必须仍是旧方案");
        assertEquals(90, afterConfirm.get(a.getId()).getAllocatedFlow());
        assertEquals(2, afterConfirm.get(b.getId()).getAllocatedStaff());
        assertEquals(10, afterConfirm.get(b.getId()).getAllocatedFlow());
        assertNull(scenarioRepository.findById(s.getId()).orElseThrow().getAppliedRunSeq());

        // 现场经理签收：会签闭环 + 场景切成优化后方案，一次做完
        OptimizationBatchDTO applied = batchService.sign(batchId, "现场经理李四");
        assertEquals("APPLIED", applied.getStatus());
        assertEquals("现场经理李四", applied.getSignedBy());
        assertNotNull(applied.getSignedAt());
        assertNotNull(applied.getAppliedAt());

        Map<Long, StaffAllocation> base = baseMap(s.getId());
        for (OptimizationBatchItemDTO item : applied.getItems()) {
            assertEquals(item.getAfterStaff(), base.get(item.getAreaId()).getAllocatedStaff(),
                    "区域" + item.getAreaName() + "人员应切成批次优化后方案");
            assertEquals(item.getAfterFlow(), base.get(item.getAreaId()).getAllocatedFlow(),
                    "区域" + item.getAreaName() + "客流应切成批次优化后方案");
        }
        assertEquals(1, scenarioRepository.findById(s.getId()).orElseThrow().getAppliedRunSeq());

        // 场景页口径（负荷测算读在用分配）：再进来看到的仍是那套优化后数字
        Map<Long, AreaLoadDTO> loadByArea = flowScenarioService.calculateLoad(s.getId()).stream()
                .collect(Collectors.toMap(AreaLoadDTO::getAreaId, x -> x));
        for (OptimizationBatchItemDTO item : applied.getItems()) {
            assertEquals(item.getAfterFlow(), loadByArea.get(item.getAreaId()).getCurrentFlow());
            assertEquals(item.getAfterStaff(), loadByArea.get(item.getAreaId()).getAllocatedStaff());
        }
    }

    // ---------- 3. 测算岗未确认，现场经理不能签收 ----------

    @Test
    void signBeforeConfirmRejected() {
        StoreArea a = area("抢签热区", 100, 10);
        StoreArea b = area("抢签冷区", 100, 10);
        FlowScenario s = scenario("未确认签收");
        alloc(s.getId(), a.getId(), 8, 90);
        alloc(s.getId(), b.getId(), 2, 10);

        Long batchId = flowScenarioService.optimize(s.getId()).getBatchId();

        BusinessConflictException ex = assertThrows(BusinessConflictException.class,
                () -> batchService.sign(batchId, "现场经理"));
        assertEquals("BATCH_NOT_CONFIRMED", ex.getCode());

        // 场景分配一行未动
        Map<Long, StaffAllocation> base = baseMap(s.getId());
        assertEquals(90, base.get(a.getId()).getAllocatedFlow());
        assertEquals(10, base.get(b.getId()).getAllocatedFlow());
    }

    // ---------- 4. 确认后又跑新一轮推演：旧批次确认作废，签收被拒并点名批次与轮次 ----------

    @Test
    void newRunAfterConfirmVoidsOldBatchAndSignFailsWithRunDetails() {
        StoreArea a = area("轮次热区", 100, 10);
        StoreArea b = area("轮次冷区", 100, 10);
        FlowScenario s = scenario("轮次作废");
        alloc(s.getId(), a.getId(), 8, 90);
        alloc(s.getId(), b.getId(), 2, 10);

        Long oldBatchId = flowScenarioService.optimize(s.getId()).getBatchId();
        batchService.confirm(oldBatchId, "测算岗");

        // 又跑了一轮新的推演
        OptimizationResultDTO secondRun = flowScenarioService.optimize(s.getId());
        assertEquals(2, secondRun.getRunSeq());

        // 旧批次上的确认已作废
        OptimizationBatchDTO oldBatch = batchService.getById(oldBatchId);
        assertEquals("STALE", oldBatch.getStatus());
        assertNotNull(oldBatch.getStaleReason());

        // 现场经理不能再拿旧批次改场景；失败说明点名：哪份批次、冻的哪一轮、场景现在哪一轮
        BusinessConflictException ex = assertThrows(BusinessConflictException.class,
                () -> batchService.sign(oldBatchId, "现场经理"));
        assertEquals("BATCH_STALE", ex.getCode());
        assertTrue(ex.getMessage().contains("#" + oldBatchId), "失败说明要指出哪一份批次: " + ex.getMessage());
        assertTrue(ex.getMessage().contains("第 1 轮"), "失败说明要指出冻的是哪一轮: " + ex.getMessage());
        assertTrue(ex.getMessage().contains("第 2 轮"), "失败说明要指出场景当前是哪一轮: " + ex.getMessage());
        assertEquals(oldBatchId, ex.getDetails().get("batchId"));
        assertEquals(1, ex.getDetails().get("frozenRunSeq"));
        assertEquals(2, ex.getDetails().get("currentRunSeq"));

        // 场景分配仍是旧方案（谁都没改成）
        Map<Long, StaffAllocation> base = baseMap(s.getId());
        assertEquals(8, base.get(a.getId()).getAllocatedStaff());
        assertEquals(90, base.get(a.getId()).getAllocatedFlow());
        assertEquals(2, base.get(b.getId()).getAllocatedStaff());
        assertEquals(10, base.get(b.getId()).getAllocatedFlow());

        // 新一轮推演的批次待会签，走完整会签仍可正常落地
        OptimizationBatchDTO newBatch = batchService.getById(secondRun.getBatchId());
        assertEquals("PENDING_CONFIRM", newBatch.getStatus());
        assertEquals(2, newBatch.getRunSeq());
        batchService.confirm(newBatch.getId(), "测算岗");
        OptimizationBatchDTO applied = batchService.sign(newBatch.getId(), "现场经理");
        assertEquals("APPLIED", applied.getStatus());
        assertEquals(2, scenarioRepository.findById(s.getId()).orElseThrow().getAppliedRunSeq());
    }

    // ---------- 5. 待确认批次同样被新一轮推演作废 ----------

    @Test
    void pendingBatchAlsoVoidedByNewRun() {
        StoreArea a = area("待确热区", 100, 10);
        StoreArea b = area("待确冷区", 100, 10);
        FlowScenario s = scenario("待确认作废");
        alloc(s.getId(), a.getId(), 8, 90);
        alloc(s.getId(), b.getId(), 2, 10);

        Long firstBatchId = flowScenarioService.optimize(s.getId()).getBatchId();
        flowScenarioService.optimize(s.getId());

        assertEquals("STALE", batchService.getById(firstBatchId).getStatus());
        BusinessConflictException ex = assertThrows(BusinessConflictException.class,
                () -> batchService.confirm(firstBatchId, "测算岗"));
        assertEquals("BATCH_STALE", ex.getCode());
    }

    // ---------- 6. 批次冻住推演当时档案：区域容量/编制后续调整不回写 ----------

    @Test
    void batchKeepsRunTimeArchiveWhenAreaConfigChangesLater() {
        StoreArea a = area("档案热区", 100, 10);
        StoreArea b = area("档案冷区", 100, 10);
        FlowScenario s = scenario("冻结档案");
        alloc(s.getId(), a.getId(), 8, 90);
        alloc(s.getId(), b.getId(), 2, 10);

        Long batchId = flowScenarioService.optimize(s.getId()).getBatchId();

        // 推演之后区域档案被改：容量 100 -> 500，编制 10 -> 50
        AreaConfigDTO bigger = new AreaConfigDTO();
        bigger.setAreaName(areaRepository.findById(a.getId()).orElseThrow().getAreaName());
        bigger.setMaxCapacity(500);
        bigger.setStaffQuota(50);
        bigger.setDescription("");
        areaService.updateArea(a.getId(), bigger);

        // 批次里仍是推演当时的 100 / 10
        OptimizationBatchItemDTO itemA = itemOf(batchService.getById(batchId), a.getId());
        assertEquals(100, itemA.getMaxCapacity(), "批次必须冻住推演当时的核定容量");
        assertEquals(10, itemA.getStaffQuota(), "批次必须冻住推演当时的编制配额");
    }

    // ---------- 7. 已落地批次不能重复签收 ----------

    @Test
    void doubleSignRejected() {
        StoreArea a = area("复签热区", 100, 10);
        StoreArea b = area("复签冷区", 100, 10);
        FlowScenario s = scenario("重复签收");
        alloc(s.getId(), a.getId(), 8, 90);
        alloc(s.getId(), b.getId(), 2, 10);

        Long batchId = flowScenarioService.optimize(s.getId()).getBatchId();
        batchService.confirm(batchId, "测算岗");
        batchService.sign(batchId, "现场经理");

        BusinessConflictException ex = assertThrows(BusinessConflictException.class,
                () -> batchService.sign(batchId, "现场经理"));
        assertEquals("BATCH_ALREADY_APPLIED", ex.getCode());
    }

    // ---------- 8. 并发签收：同一份批次只落地一次 ----------

    @Test
    @org.springframework.transaction.annotation.Transactional(propagation =
            org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    void concurrentSignsOnlyOneApplies() throws Exception {
        org.springframework.transaction.support.TransactionTemplate tx =
                new org.springframework.transaction.support.TransactionTemplate(txManager);
        tx.executeWithoutResult(status -> {
            batchItemRepository.deleteAll();
            batchRepository.deleteAll();
            injectionRepository.deleteAll();
            closureRepository.deleteAll();
            allocationRepository.deleteAll();
            areaRepository.deleteAll();
            scenarioRepository.deleteAll();
        });
        Long[] ids = tx.execute(status -> {
            StoreArea a = area("并发热区", 100, 10);
            StoreArea b = area("并发冷区", 100, 10);
            FlowScenario s = scenario("并发签收");
            alloc(s.getId(), a.getId(), 8, 90);
            alloc(s.getId(), b.getId(), 2, 10);
            return new Long[]{s.getId(), a.getId(), b.getId()};
        });
        long scenarioId = ids[0];
        long aId = ids[1];
        long bId = ids[2];

        Long batchId = flowScenarioService.optimize(scenarioId).getBatchId();
        batchService.confirm(batchId, "测算岗");

        // 两个现场经理同时签收同一份批次
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<String>> futures = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            String who = "现场经理" + (i + 1);
            futures.add(pool.submit(() -> {
                start.await();
                try {
                    batchService.sign(batchId, who);
                    return "OK";
                } catch (BusinessConflictException e) {
                    return e.getCode();
                }
            }));
        }
        start.countDown();
        List<String> results = new ArrayList<>();
        for (Future<String> f : futures) {
            results.add(f.get(30, TimeUnit.SECONDS));
        }
        pool.shutdown();

        assertEquals(1, results.stream().filter("OK"::equals).count(), "只允许一次签收落地");
        assertEquals(1, results.stream().filter("BATCH_ALREADY_APPLIED"::equals).count(),
                "另一次必须因已落地被拒");

        // 批次终态 APPLIED，场景分配就是批次冻结的优化后方案
        OptimizationBatchDTO batch = batchService.getById(batchId);
        assertEquals("APPLIED", batch.getStatus());
        Map<Long, StaffAllocation> base = baseMap(scenarioId);
        for (OptimizationBatchItemDTO item : batch.getItems()) {
            assertEquals(item.getAfterStaff(), base.get(item.getAreaId()).getAllocatedStaff());
            assertEquals(item.getAfterFlow(), base.get(item.getAreaId()).getAllocatedFlow());
        }
        assertEquals(1, scenarioRepository.findById(scenarioId).orElseThrow().getAppliedRunSeq());
        assertTrue(base.get(aId).getAllocatedFlow() != 90 || base.get(bId).getAllocatedFlow() != 10,
                "落地后场景应已离开旧方案");

        tx.executeWithoutResult(status -> {
            batchItemRepository.deleteAll();
            batchRepository.deleteAll();
            injectionRepository.deleteAll();
            closureRepository.deleteAll();
            allocationRepository.deleteAll();
            areaRepository.deleteAll();
            scenarioRepository.deleteAll();
        });
    }

    // ---------- 9. 关掉页面再打开：批次仍是已落地，场景仍是那套优化后数字 ----------

    @Test
    void appliedBatchPersistsAcrossReloads() {
        StoreArea a = area("持久热区", 100, 10);
        StoreArea b = area("持久冷区", 100, 10);
        FlowScenario s = scenario("落地持久");
        alloc(s.getId(), a.getId(), 8, 90);
        alloc(s.getId(), b.getId(), 2, 10);

        Long batchId = flowScenarioService.optimize(s.getId()).getBatchId();
        batchService.confirm(batchId, "测算岗");
        OptimizationBatchDTO applied = batchService.sign(batchId, "现场经理");

        // 重新从库里查（模拟关掉页面再打开）
        OptimizationBatchDTO reloaded = batchService.getById(batchId);
        assertEquals("APPLIED", reloaded.getStatus());
        assertEquals(2, reloaded.getItems().size());
        assertFalse(batchService.list(s.getId()).isEmpty());
        assertEquals("APPLIED", batchService.list(s.getId()).get(0).getStatus());

        Map<Long, AreaLoadDTO> loadByArea = flowScenarioService.calculateLoad(s.getId()).stream()
                .collect(Collectors.toMap(AreaLoadDTO::getAreaId, x -> x));
        for (OptimizationBatchItemDTO item : applied.getItems()) {
            assertEquals(item.getAfterFlow(), loadByArea.get(item.getAreaId()).getCurrentFlow(),
                    "再进场景页仍应是批次落地的那套优化后数字");
            assertEquals(item.getAfterStaff(), loadByArea.get(item.getAreaId()).getAllocatedStaff());
        }
    }

    // ---------- 10. 批次涉及区域被封区生效：禁止落地写回 ----------

    @Test
    void signBlockedWhenBatchAreaClosedAfterConfirm() {
        StoreArea a = area("将封区", 100, 10);
        StoreArea b = area("承接一", 100, 10);
        StoreArea c = area("承接二", 100, 10);
        FlowScenario s = scenario("落地遇封区");
        alloc(s.getId(), a.getId(), 8, 90);
        alloc(s.getId(), b.getId(), 2, 10);
        alloc(s.getId(), c.getId(), 1, 5);

        Long batchId = flowScenarioService.optimize(s.getId()).getBatchId();
        batchService.confirm(batchId, "测算岗");

        // 确认之后、签收之前，A 被临时封区（清 0 回灌到 B/C）
        ClosureSubmitRequest req = new ClosureSubmitRequest();
        req.setScenarioId(s.getId());
        req.setAreaId(a.getId());
        req.setReason("设备故障");
        AreaClosureDTO closure = closureService.submitClosure(req);
        assertEquals("EFFECTIVE", closure.getStatus());

        // 批次里冻结了 A 的写回方案，但 A 封区生效中：整单拒绝落地
        BusinessConflictException ex = assertThrows(BusinessConflictException.class,
                () -> batchService.sign(batchId, "现场经理"));
        assertEquals("BATCH_BLOCKED_AREA_CLOSED", ex.getCode());

        // 批次仍停在待签收，场景分配保持封区后的样子（A 为 0）
        assertEquals("CONFIRMED", batchService.getById(batchId).getStatus());
        Map<Long, StaffAllocation> base = baseMap(s.getId());
        assertEquals(0, base.get(a.getId()).getAllocatedStaff());
        assertEquals(0, base.get(a.getId()).getAllocatedFlow());
    }
}
