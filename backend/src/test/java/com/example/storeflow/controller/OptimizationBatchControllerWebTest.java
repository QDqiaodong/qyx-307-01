package com.example.storeflow.controller;

import com.example.storeflow.TestRedisConfig;
import com.example.storeflow.entity.FlowScenario;
import com.example.storeflow.entity.StaffAllocation;
import com.example.storeflow.entity.StoreArea;
import com.example.storeflow.repository.FlowScenarioRepository;
import com.example.storeflow.repository.StaffAllocationRepository;
import com.example.storeflow.repository.StoreAreaRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * 落地批次会签 HTTP 端到端冒烟：推演冻批次 / 未确认签收 409 / 确认后签收前场景仍是旧方案 /
 * 签收落地后场景页是优化后数字 / 确认后新一轮推演作废旧批次（409 报文点名批次与轮次）。
 */
@SpringBootTest(classes = {com.example.storeflow.StoreFlowApplication.class, TestRedisConfig.class})
@AutoConfigureMockMvc
@Transactional
class OptimizationBatchControllerWebTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
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
        s.setScenarioName("web批次-" + UUID.randomUUID());
        s.setFestivalName("节");
        s.setEstimatedTotalFlow(100);
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

    private long runOptimize(long sid) throws Exception {
        String resp = mockMvc.perform(post("/api/scenarios/" + sid + "/optimize"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.runSeq").isNumber())
                .andExpect(jsonPath("$.batchId").isNumber())
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        return objectMapper.readTree(resp).get("batchId").asLong();
    }

    private JsonNode loadOf(long sid, long areaId) throws Exception {
        String resp = mockMvc.perform(get("/api/scenarios/" + sid + "/load"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        for (JsonNode row : objectMapper.readTree(resp)) {
            if (row.get("areaId").asLong() == areaId) {
                return row;
            }
        }
        fail("负荷测算里找不到区域 " + areaId);
        return null;
    }

    @Test
    void coSignFlowSwitchesScenarioOnlyAfterBothApprovals() throws Exception {
        StoreArea a = area("web热区", 100, 10);
        StoreArea b = area("web冷区", 100, 10);
        long sid = scenario();
        alloc(sid, a.getId(), 8, 90);
        alloc(sid, b.getId(), 2, 10);

        // 1) 推演冻批次：返回批次号，批次里冻住当时档案与前后分配
        long batchId = runOptimize(sid);
        String listResp = mockMvc.perform(get("/api/batches?scenarioId=" + sid))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].status").value("PENDING_CONFIRM"))
                .andExpect(jsonPath("$[0].runSeq").value(1))
                .andExpect(jsonPath("$[0].scenarioCurrentRunSeq").value(1))
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        JsonNode batchNode = objectMapper.readTree(listResp).get(0);
        JsonNode itemA = null;
        for (JsonNode item : batchNode.get("items")) {
            if (item.get("areaId").asLong() == a.getId()) {
                itemA = item;
            }
        }
        assertNotNull(itemA, "批次必须冻结每块区域的快照");
        assertEquals(100, itemA.get("maxCapacity").asInt(), "冻结核定容量");
        assertEquals(10, itemA.get("staffQuota").asInt(), "冻结编制配额");
        assertEquals(8, itemA.get("beforeStaff").asInt(), "冻结优化前人员");
        assertEquals(90, itemA.get("beforeFlow").asInt(), "冻结优化前客流");
        int afterFlowA = itemA.get("afterFlow").asInt();
        int afterStaffA = itemA.get("afterStaff").asInt();

        // 2) 测算岗未确认，现场经理签收 -> 409
        mockMvc.perform(post("/api/batches/" + batchId + "/sign")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"operator\":\"现场经理\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("BATCH_NOT_CONFIRMED"));

        // 3) 测算岗确认 -> 200 CONFIRMED；场景仍是旧方案
        mockMvc.perform(post("/api/batches/" + batchId + "/confirm")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"operator\":\"测算岗小王\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.confirmedBy").value("测算岗小王"));
        assertEquals(90, loadOf(sid, a.getId()).get("currentFlow").asInt(),
                "测算确认后、经理签收前，场景必须仍是旧方案");

        // 4) 现场经理签收 -> 200 APPLIED；场景页口径已是批次优化后数字
        mockMvc.perform(post("/api/batches/" + batchId + "/sign")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"operator\":\"现场经理老赵\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPLIED"))
                .andExpect(jsonPath("$.signedBy").value("现场经理老赵"))
                .andExpect(jsonPath("$.scenarioAppliedRunSeq").value(1));
        assertEquals(afterFlowA, loadOf(sid, a.getId()).get("currentFlow").asInt(),
                "会签落地后场景页应显示批次冻结的优化后客流");
        assertEquals(afterStaffA, loadOf(sid, a.getId()).get("allocatedStaff").asInt(),
                "会签落地后场景页应显示批次冻结的优化后人员");

        // 5) 重复签收 -> 409
        mockMvc.perform(post("/api/batches/" + batchId + "/sign")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"operator\":\"现场经理\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("BATCH_ALREADY_APPLIED"));
    }

    @Test
    void newRunAfterConfirmVoidsBatchAndSign409ShowsBatchAndRuns() throws Exception {
        StoreArea a = area("web轮次热", 100, 10);
        StoreArea b = area("web轮次冷", 100, 10);
        long sid = scenario();
        alloc(sid, a.getId(), 8, 90);
        alloc(sid, b.getId(), 2, 10);

        // 第 1 轮推演 -> 测算岗确认 -> 又跑第 2 轮推演
        long batchId = runOptimize(sid);
        mockMvc.perform(post("/api/batches/" + batchId + "/confirm")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"operator\":\"测算岗\"}"))
                .andExpect(status().isOk());
        runOptimize(sid);

        // 旧批次已作废；现场经理再拿旧批次签收 -> 409，报文点名批次、冻结轮次、当前轮次
        String resp = mockMvc.perform(post("/api/batches/" + batchId + "/sign")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"operator\":\"现场经理\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("BATCH_STALE"))
                .andExpect(jsonPath("$.data.batchId").value(batchId))
                .andExpect(jsonPath("$.data.frozenRunSeq").value(1))
                .andExpect(jsonPath("$.data.currentRunSeq").value(2))
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        String message = objectMapper.readTree(resp).get("message").asText();
        assertTrue(message.contains("#" + batchId), "失败说明要让人看见是哪一份批次: " + message);
        assertTrue(message.contains("第 1 轮"), "失败说明要让人看见它冻的是哪一轮: " + message);
        assertTrue(message.contains("第 2 轮"), "失败说明要让人看见场景现在是哪一轮: " + message);

        // 旧批次在列表里就是作废态，关掉页面再打开也不会复活
        mockMvc.perform(get("/api/batches/" + batchId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("STALE"))
                .andExpect(jsonPath("$.staleReason").isString());

        // 场景分配仍是旧方案，谁都没改成
        assertEquals(90, loadOf(sid, a.getId()).get("currentFlow").asInt());
        assertEquals(8, loadOf(sid, a.getId()).get("allocatedStaff").asInt());
    }

    @Test
    void unknownBatchReturns404() throws Exception {
        mockMvc.perform(post("/api/batches/999999/confirm")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isNotFound());
    }
}
