package com.example.storeflow.controller;

import com.example.storeflow.TestRedisConfig;
import com.example.storeflow.entity.FlowScenario;
import com.example.storeflow.entity.StaffAllocation;
import com.example.storeflow.entity.StoreArea;
import com.example.storeflow.repository.FlowScenarioRepository;
import com.example.storeflow.repository.StaffAllocationRepository;
import com.example.storeflow.repository.StoreAreaRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * 推演落地批次会签的 HTTP 端到端冒烟：
 * 推演即冻批次、未确认不能签收、确认后场景仍旧方案、签收后场景改优化后方案、
 * 新一轮作废旧批次签收 409 且回执点名批次号/冻结轮次/当前轮次。
 */
@SpringBootTest(classes = {com.example.storeflow.StoreFlowApplication.class, TestRedisConfig.class})
@AutoConfigureMockMvc
@Transactional
class OptimizationBatchControllerWebTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private com.fasterxml.jackson.databind.ObjectMapper objectMapper;
    @Autowired private StoreAreaRepository areaRepository;
    @Autowired private FlowScenarioRepository scenarioRepository;
    @Autowired private StaffAllocationRepository allocationRepository;

    private StoreArea area(String name, int cap, int quota) {
        StoreArea a = new StoreArea();
        a.setAreaName(name + "-" + UUID.randomUUID());
        a.setMaxCapacity(cap);
        a.setStaffQuota(quota);
        a.setRiskFlag(false);
        a.setRiskLevel(0);
        return areaRepository.save(a);
    }

    private long scenario() {
        FlowScenario s = new FlowScenario();
        s.setScenarioName("batch-web-" + UUID.randomUUID());
        s.setFestivalName("节");
        s.setEstimatedTotalFlow(1000);
        return scenarioRepository.save(s).getId();
    }

    private void alloc(long sid, long aid, int staff, int flow) {
        StaffAllocation a = new StaffAllocation();
        a.setScenarioId(sid);
        a.setAreaId(aid);
        a.setAllocatedStaff(staff);
        a.setAllocatedFlow(flow);
        a.setIsOptimized(false);
        allocationRepository.save(a);
    }

    private int flowOf(long sid, long aid) {
        StaffAllocation a = allocationRepository.findBaseAllocation(sid, aid);
        return a == null || a.getAllocatedFlow() == null ? 0 : a.getAllocatedFlow();
    }

    @Test
    void freezeConfirmSignFlowAndSupersedeReceipt() throws Exception {
        StoreArea hot = area("web过载", 100, 10);
        StoreArea low = area("web低载", 200, 20);
        long sid = scenario();
        alloc(sid, hot.getId(), 9, 95);
        alloc(sid, low.getId(), 1, 10);

        // 1) 推演 -> 200，返回轮次与批次 id；场景在用分配仍是旧方案
        String opt = mockMvc.perform(post("/api/scenarios/" + sid + "/optimize"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.optimizationRound").value(1))
                .andExpect(jsonPath("$.batchId").isNumber())
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        long batchId = objectMapper.readTree(opt).get("batchId").asLong();
        org.junit.jupiter.api.Assertions.assertEquals(10, flowOf(sid, low.getId()), "推演后场景必须仍是旧方案");

        // 2) 批次台账可见，DRAFT，冻住容量/编制/前后分配
        mockMvc.perform(get("/api/batches?scenarioId=" + sid))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(batchId))
                .andExpect(jsonPath("$[0].status").value("DRAFT"))
                .andExpect(jsonPath("$[0].optimizationRound").value(1))
                .andExpect(jsonPath("$[0].items.length()").value(2));

        // 3) 测算未确认，现场经理先签收 -> 409
        mockMvc.perform(post("/api/batches/" + batchId + "/sign")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("BATCH_NOT_CONFIRMED"));

        // 4) 测算确认 -> CONFIRMED；场景仍旧
        mockMvc.perform(post("/api/batches/" + batchId + "/confirm")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"note\":\"数字对\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.analystConfirmedAt").isNotEmpty());
        org.junit.jupiter.api.Assertions.assertEquals(10, flowOf(sid, low.getId()), "确认后签收前场景必须仍旧");

        // 5) 又跑一轮：旧批次作废
        mockMvc.perform(post("/api/scenarios/" + sid + "/optimize"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.optimizationRound").value(2));
        mockMvc.perform(get("/api/batches/" + batchId))
                .andExpect(jsonPath("$.status").value("SUPERSEDED"))
                .andExpect(jsonPath("$.failure.frozenRound").value(1))
                .andExpect(jsonPath("$.failure.currentRound").value(2));

        // 6) 现场经理拿旧批次签收 -> 409，回执点名批次号/冻的轮次/当前轮次
        mockMvc.perform(post("/api/batches/" + batchId + "/sign")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("BATCH_SUPERSEDED"))
                .andExpect(jsonPath("$.data.batchId").value(batchId))
                .andExpect(jsonPath("$.data.frozenRound").value(1))
                .andExpect(jsonPath("$.data.currentRound").value(2));
        org.junit.jupiter.api.Assertions.assertEquals(10, flowOf(sid, low.getId()), "旧批次签收失败，场景必须不动");

        // 7) 第二轮批次正常走两步会签，签收后场景改成优化后方案
        String opt2 = mockMvc.perform(get("/api/batches?scenarioId=" + sid))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        long secondBatchId = objectMapper.readTree(opt2).get(0).get("id").asLong();
        org.junit.jupiter.api.Assertions.assertNotEquals(batchId, secondBatchId);
        mockMvc.perform(post("/api/batches/" + secondBatchId + "/confirm")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(jsonPath("$.status").value("CONFIRMED"));
        mockMvc.perform(post("/api/batches/" + secondBatchId + "/sign")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"note\":\"签收\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("LANDED"))
                .andExpect(jsonPath("$.landedAt").isNotEmpty());
        org.junit.jupiter.api.Assertions.assertTrue(flowOf(sid, low.getId()) > 10,
                "签收落地后低载区客流应按优化后方案增加，实际: " + flowOf(sid, low.getId()));

        // 8) 已落地批次仍是 LANDED（再进页面可对账）
        mockMvc.perform(get("/api/batches/" + secondBatchId))
                .andExpect(jsonPath("$.status").value("LANDED"));
    }
}
