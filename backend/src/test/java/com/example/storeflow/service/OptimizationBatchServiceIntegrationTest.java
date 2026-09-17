package com.example.storeflow.service;

import com.example.storeflow.TestRedisConfig;
import com.example.storeflow.dto.OptimizationBatchDTO;
import com.example.storeflow.dto.OptimizationResultDTO;
import com.example.storeflow.entity.BatchStatus;
import com.example.storeflow.entity.FlowScenario;
import com.example.storeflow.entity.OptimizationBatch;
import com.example.storeflow.entity.StaffAllocation;
import com.example.storeflow.entity.StoreArea;
import com.example.storeflow.exception.BusinessConflictException;
import com.example.storeflow.repository.FlowScenarioRepository;
import com.example.storeflow.repository.OptimizationBatchRepository;
import com.example.storeflow.repository.StaffAllocationRepository;
import com.example.storeflow.repository.StoreAreaRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 推演落地批次两段会签的端到端集成测试（H2）。
 *
 * <p>覆盖：
 * <ol>
 *   <li>推演即冻结批次（冻容量/编制/前后分配），但在用分配不变；</li>
 *   <li>测算确认只改批次、场景仍旧方案；现场经理未确认不能先行签收；</li>
 *   <li>两步会签完成的同一事务把场景改成优化后方案，再进页面仍是这套数字（持久化）；</li>
 *   <li>测算确认后又跑一轮推演，旧批次确认作废，现场经理签收旧批次被 409，
 *       回执点名批次号、冻的轮次、场景当前轮次；</li>
 *   <li>优化后数字超当前额度 / 目标区域封区生效时，签收整单拒绝、场景一行不动。</li>
 * </ol>
 */
@SpringBootTest(classes = {com.example.storeflow.StoreFlowApplication.class, TestRedisConfig.class})
@Transactional
class OptimizationBatchServiceIntegrationTest {

    @Autowired private FlowScenarioService scenarioService;
    @Autowired private OptimizationBatchService batchService;
    @Autowired private ClosureService closureService;
    @Autowired private StoreAreaService areaService;
    @Autowired private StoreAreaRepository areaRepository;
    @Autowired private FlowScenarioRepository scenarioRepository;
    @Autowired private StaffAllocationRepository allocationRepository;
    @Autowired private OptimizationBatchRepository batchRepository;

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

    private java.util.Map<Long, StaffAllocation> baseMap(long scenarioId) {
        return allocationRepository.findByScenarioIdAndIsOptimized(scenarioId, false).stream()
                .collect(Collectors.toMap(StaffAllocation::getAreaId, a -> a));
    }

    private int flowOf(long scenarioId, long areaId) {
        StaffAllocation a = allocationRepository.findBaseAllocation(scenarioId, areaId);
        return a == null || a.getAllocatedFlow() == null ? 0 : a.getAllocatedFlow();
    }

    private int staffOf(long scenarioId, long areaId) {
        StaffAllocation a = allocationRepository.findBaseAllocation(scenarioId, areaId);
        return a == null || a.getAllocatedStaff() == null ? 0 : a.getAllocatedStaff();
    }

    /** 两块过载区 + 一块低载区，保证推演一定产生调配，优化前后不同。 */
    private Object[] overloadedScenario() {
        StoreArea hot1 = area("过载A", 100, 10);
        StoreArea hot2 = area("过载B", 100, 10);
        StoreArea low = area("低载C", 200, 20);
        FlowScenario s = scenario("会签场景");
        alloc(s.getId(), hot1.getId(), 9, 95);
        alloc(s.getId(), hot2.getId(), 9, 90);
        alloc(s.getId(), low.getId(), 1, 10);
        return new Object[]{s, hot1, hot2, low};
    }

    // ---------- 1. 推演冻结批次：冻住档案，但场景在用分配不变 ----------

    @Test
    void optimizeFreezesBatchButKeepsScenarioOnOldPlan() {
        Object[] setup = overloadedScenario();
        FlowScenario s = (FlowScenario) setup[0];
        StoreArea low = (StoreArea) setup[3];

        OptimizationResultDTO result = scenarioService.optimize(s.getId());
        assertEquals(1, result.getOptimizationRound());
        assertNotNull(result.getBatchId());

        // 场景在用分配仍是优化前旧方案（低载区依旧 10）
        assertEquals(10, flowOf(s.getId(), low.getId()));
        assertEquals(1, staffOf(s.getId(), low.getId()));

        OptimizationBatchDTO batch = batchService.getById(result.getBatchId());
        assertEquals("DRAFT", batch.getStatus());
        assertEquals(1, batch.getOptimizationRound());
        assertTrue(batch.getCanAnalystConfirm());
        assertFalse(batch.getCanManagerSign());
        // 每块区域冻住核定容量、编制、优化前/后分配
        assertEquals(3, batch.getItems().size());
        OptimizationBatchDTO frozen = batch;
        frozen.getItems().forEach(i -> {
            assertTrue(i.getFrozenMaxCapacity() > 0);
            assertTrue(i.getFrozenStaffQuota() > 0);
            assertNotNull(i.getBeforeStaff());
            assertNotNull(i.getAfterStaff());
        });
        // 总量守恒
        assertEquals(batch.getBeforeTotalStaff(), batch.getAfterTotalStaff());
        assertEquals(batch.getBeforeTotalFlow(), batch.getAfterTotalFlow());
    }

    // ---------- 2. 测算确认后仍是旧方案；未确认现场经理不能先行签收 ----------

    @Test
    void analystConfirmDoesNotChangeScenarioAndManagerCannotSignBeforeConfirm() {
        Object[] setup = overloadedScenario();
        FlowScenario s = (FlowScenario) setup[0];
        StoreArea low = (StoreArea) setup[3];

        Long batchId = scenarioService.optimize(s.getId()).getBatchId();

        // 现场经理想跳过测算确认直接签收 -> 409
        BusinessConflictException ex = assertThrows(BusinessConflictException.class,
                () -> batchService.managerSign(batchId, "现场先签收"));
        assertEquals("BATCH_NOT_CONFIRMED", ex.getCode());

        // 测算确认
        OptimizationBatchDTO confirmed = batchService.analystConfirm(batchId, "数字核对无误");
        assertEquals("CONFIRMED", confirmed.getStatus());
        assertNotNull(confirmed.getAnalystConfirmedAt());
        assertTrue(confirmed.getCanManagerSign());

        // 关键：确认之后、签收之前，场景分配必须仍是旧方案
        assertEquals(10, flowOf(s.getId(), low.getId()));
        assertEquals(1, staffOf(s.getId(), low.getId()));
    }

    // ---------- 3. 两步会签完成：同事务改成优化后方案，持久化可复查 ----------

    @Test
    void managerSignLandsOptimizedPlanAtomicallyAndPersists() {
        Object[] setup = overloadedScenario();
        FlowScenario s = (FlowScenario) setup[0];
        StoreArea hot1 = (StoreArea) setup[1];
        StoreArea hot2 = (StoreArea) setup[2];
        StoreArea low = (StoreArea) setup[3];

        OptimizationResultDTO result = scenarioService.optimize(s.getId());
        Long batchId = result.getBatchId();
        // 记录冻住的优化后方案期望值
        OptimizationBatchDTO before = batchService.getById(batchId);
        var expectAfter = before.getItems().stream()
                .collect(Collectors.toMap(i -> i.getAreaId(), i -> new int[]{i.getAfterStaff(), i.getAfterFlow()}));

        batchService.analystConfirm(batchId, "ok");
        OptimizationBatchDTO landed = batchService.managerSign(batchId, "现场签收");

        assertEquals("LANDED", landed.getStatus());
        assertNotNull(landed.getManagerSignedAt());
        assertNotNull(landed.getLandedAt());

        // 场景在用分配已逐块改成冻住的优化后数字
        for (var e : expectAfter.entrySet()) {
            assertEquals(e.getValue()[0], staffOf(s.getId(), e.getKey()), "人员未按批次写回");
            assertEquals(e.getValue()[1], flowOf(s.getId(), e.getKey()), "客流未按批次写回");
        }
        // 低载区确实从旧方案 1/10 变了（证明改的是优化后方案）
        assertNotEquals(10, flowOf(s.getId(), low.getId()));
        // 过载区缓解
        assertTrue(flowOf(s.getId(), hot1.getId()) <= 100);
        assertTrue(flowOf(s.getId(), hot2.getId()) <= 100);

        // 再进页面（重新查询）：批次仍 LANDED，场景仍是那一套优化后数字
        OptimizationBatchDTO reload = batchService.getById(batchId);
        assertEquals("LANDED", reload.getStatus());
        for (var e : expectAfter.entrySet()) {
            assertEquals(e.getValue()[1], flowOf(s.getId(), e.getKey()));
        }
        // 草稿已清空，避免和在用分配两本账
        assertTrue(allocationRepository.findByScenarioIdAndIsOptimized(s.getId(), true).isEmpty());
    }

    // ---------- 4. 测算确认后又跑一轮：旧批次作废，现场经理签收旧批次 409 且回执点名 ----------

    @Test
    void newRoundInvalidatesConfirmedOldBatchAndSignFailsWithDetailedReceipt() {
        Object[] setup = overloadedScenario();
        FlowScenario s = (FlowScenario) setup[0];
        StoreArea low = (StoreArea) setup[3];

        // 第一轮：冻批次并测算确认（现场尚未签收）
        Long firstBatchId = scenarioService.optimize(s.getId()).getBatchId();
        batchService.analystConfirm(firstBatchId, "第一轮确认");
        int lowFlowAfterRound1 = batchService.getById(firstBatchId).getItems().stream()
                .filter(i -> i.getAreaId().equals(low.getId())).findFirst().orElseThrow().getAfterFlow();

        // 又跑一轮新推演
        OptimizationResultDTO round2 = scenarioService.optimize(s.getId());
        assertEquals(2, round2.getOptimizationRound());
        Long secondBatchId = round2.getBatchId();
        assertNotEquals(firstBatchId, secondBatchId);

        // 旧批次确认作废
        OptimizationBatchDTO old = batchService.getById(firstBatchId);
        assertEquals("SUPERSEDED", old.getStatus());
        assertFalse(old.getCanManagerSign());
        assertNotNull(old.getFailure());
        assertEquals(firstBatchId, old.getFailure().getBatchId());
        assertEquals(1, old.getFailure().getFrozenRound());
        assertEquals(2, old.getFailure().getCurrentRound());
        assertNotNull(old.getFailure().getMessage());
        assertTrue(old.getFailure().getMessage().contains("第1轮"));
        assertTrue(old.getFailure().getMessage().contains("第2轮"));

        // 场景始终还是旧方案（两轮都没人签收）
        assertEquals(10, flowOf(s.getId(), low.getId()));

        // 现场经理拿旧批次去改场景 -> 409，回执结构化点名
        BusinessConflictException ex = assertThrows(BusinessConflictException.class,
                () -> batchService.managerSign(firstBatchId, "拿旧批次落地"));
        assertEquals("BATCH_SUPERSEDED", ex.getCode());
        assertNotNull(ex.getData());
        // 旧批次的优化后数字没有被写进场景
        assertNotEquals(lowFlowAfterRound1, flowOf(s.getId(), low.getId()));

        // 新一轮批次正常可走会签
        OptimizationBatchDTO fresh = batchService.analystConfirm(secondBatchId, "第二轮确认");
        assertEquals("CONFIRMED", fresh.getStatus());
        OptimizationBatchDTO landed = batchService.managerSign(secondBatchId, "现场签收第二轮");
        assertEquals("LANDED", landed.getStatus());
    }

    // ---------- 5. 批次冻住后区域容量改小：签收拒绝、场景不动 ----------

    @Test
    void signingBlockedWhenFrozenAfterPlanExceedsCurrentQuota() {
        Object[] setup = overloadedScenario();
        FlowScenario s = (FlowScenario) setup[0];
        StoreArea low = (StoreArea) setup[3];

        Long batchId = scenarioService.optimize(s.getId()).getBatchId();
        int afterFlowToLow = batchService.getById(batchId).getItems().stream()
                .filter(i -> i.getAreaId().equals(low.getId())).findFirst().orElseThrow().getAfterFlow();
        assertTrue(afterFlowToLow > 10, "测试前提：优化后低载区客流应大于其优化前 10");

        batchService.analystConfirm(batchId, "ok");

        // 把低载区容量改到连优化后客流都放不下
        com.example.storeflow.dto.AreaConfigDTO smaller = new com.example.storeflow.dto.AreaConfigDTO();
        smaller.setAreaName(areaRepository.findById(low.getId()).orElseThrow().getAreaName());
        smaller.setMaxCapacity(afterFlowToLow - 5);
        smaller.setStaffQuota(low.getStaffQuota());
        smaller.setDescription("");
        areaService.updateArea(low.getId(), smaller);

        BusinessConflictException ex = assertThrows(BusinessConflictException.class,
                () -> batchService.managerSign(batchId, "试图签收"));
        assertEquals("BATCH_LANDING_BLOCKED", ex.getCode());

        // 场景一行不动，批次仍停在 CONFIRMED
        assertEquals(10, flowOf(s.getId(), low.getId()));
        assertEquals(BatchStatus.CONFIRMED, batchRepository.findById(batchId).orElseThrow().getStatus());
    }

    // ---------- 6. 优化后目标区域在签收前被封区生效：签收拒绝、场景不动 ----------

    @Test
    void signingBlockedWhenTargetAreaIsUnderLiveClosure() {
        Object[] setup = overloadedScenario();
        FlowScenario s = (FlowScenario) setup[0];
        StoreArea low = (StoreArea) setup[3];

        Long batchId = scenarioService.optimize(s.getId()).getBatchId();
        int afterFlowToLow = batchService.getById(batchId).getItems().stream()
                .filter(i -> i.getAreaId().equals(low.getId())).findFirst().orElseThrow().getAfterFlow();
        assertTrue(afterFlowToLow > 0);
        batchService.analystConfirm(batchId, "ok");

        // 现场在签收前把低载区封了（它有 10 客流可疏散到两块过载区；过载区各有 5 容量余量）
        var closed = closureService.submitClosure(new com.example.storeflow.dto.ClosureSubmitRequest() {{
            setScenarioId(s.getId());
            setAreaId(low.getId());
            setReason("临检");
        }});
        assertEquals("EFFECTIVE", closed.getStatus(), "测试前提：封区应成功");

        // 批次优化后方案要把客流写回仍封区生效的 low -> 拒绝
        BusinessConflictException ex = assertThrows(BusinessConflictException.class,
                () -> batchService.managerSign(batchId, "试图签收"));
        assertEquals("BATCH_LANDING_BLOCKED", ex.getCode());
        assertEquals(BatchStatus.CONFIRMED, batchRepository.findById(batchId).orElseThrow().getStatus());
        // 被封区域保持清 0，没有被优化后数字写回
        assertEquals(0, flowOf(s.getId(), low.getId()));
    }

    // ---------- 7. 已落地批次不被新一轮推演作废，档案仍可对账 ----------

    @Test
    void landedBatchStaysLandedWhenAnotherRoundRuns() {
        Object[] setup = overloadedScenario();
        FlowScenario s = (FlowScenario) setup[0];

        Long firstId = scenarioService.optimize(s.getId()).getBatchId();
        batchService.analystConfirm(firstId, "ok");
        batchService.managerSign(firstId, "签收");
        assertEquals(BatchStatus.LANDED, batchRepository.findById(firstId).orElseThrow().getStatus());

        // 再跑一轮
        Long secondId = scenarioService.optimize(s.getId()).getBatchId();
        assertEquals(BatchStatus.LANDED, batchRepository.findById(firstId).orElseThrow().getStatus(),
                "已落地批次是终态，不被新一轮作废");
        OptimizationBatchDTO first = batchService.getById(firstId);
        assertEquals("LANDED", first.getStatus());
        assertNotNull(first.getItems());
        assertEquals(BatchStatus.DRAFT, batchRepository.findById(secondId).orElseThrow().getStatus());
    }
}
