package com.example.storeflow.controller;

import com.example.storeflow.TestRedisConfig;
import com.example.storeflow.entity.*;
import com.example.storeflow.repository.*;
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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * 封区控制器 HTTP 端到端冒烟：验证提交成功 / 失败回执 / 写回 409 / 列表持久可见 的 JSON 形态。
 */
@SpringBootTest(classes = {com.example.storeflow.StoreFlowApplication.class, TestRedisConfig.class})
@AutoConfigureMockMvc
@Transactional
class ClosureControllerWebTest {

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
        s.setScenarioName("web-" + UUID.randomUUID());
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

    private String body(long scenarioId, long areaId, String reason) {
        return "{\"scenarioId\":" + scenarioId + ",\"areaId\":" + areaId
                + ",\"reason\":\"" + reason + "\"}";
    }

    @Test
    void happyPathThenWriteBackBlockedThenFailurePersists() throws Exception {
        StoreArea closed = area("web封", 100, 10);
        StoreArea recv = area("web收", 100, 10);
        long sid = scenario();
        alloc(sid, closed.getId(), 4, 40);
        alloc(sid, recv.getId(), 1, 10);

        // 1) 提交成功 200 EFFECTIVE，被封区清零、回灌有落地
        String resp = mockMvc.perform(post("/api/closures")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(sid, closed.getId(), "设备故障")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("EFFECTIVE"))
                .andExpect(jsonPath("$.live").value(true))
                .andExpect(jsonPath("$.evacuatedFlow").value(40))
                .andExpect(jsonPath("$.injections[0].receiverAreaId").value(recv.getId()))
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        long closureId = objectMapper.readTree(resp).get("id").asLong();

        // 2) 生效期内手工写回被封区域 -> 409 AREA_CLOSED
        mockMvc.perform(put("/api/scenarios/" + sid + "/allocation")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"areaId\":" + closed.getId() + ",\"staff\":3,\"flow\":30}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("AREA_CLOSED"));

        // 3) 重复封同一对 -> 409
        mockMvc.perform(post("/api/closures")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(sid, closed.getId(), "x")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("AREA_ALREADY_CLOSED"));

        // 4) 另一个场景承接不下 -> 200 FAILED，回执可解析、点名缺口
        StoreArea c2 = area("web封2", 100, 10);
        StoreArea sat = area("web饱和", 100, 10);
        long sid2 = scenario();
        alloc(sid2, c2.getId(), 9, 90);
        alloc(sid2, sat.getId(), 9, 95);
        // 档案里的 closed/recv 在 sid2 也存在，占满避免成为空承接区兜底
        alloc(sid2, closed.getId(), 10, 100);
        alloc(sid2, recv.getId(), 10, 100);
        String failResp = mockMvc.perform(post("/api/closures")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(sid2, c2.getId(), "客流顶满")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.failure.shortFlow").value(85))
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        JsonNode failNode = objectMapper.readTree(failResp);
        // 回执逐块列出承接区域（按区域 id 排序），其中必须包含饱和区 sat
        // 回执逐块列出承接区域（按区域 id 排序），其中必须有一块“剩 5 容量/1 编制”的饱和区
        JsonNode satView = null;
        for (JsonNode r : failNode.get("failure").get("receivers")) {
            if (r.get("remainingFlow").asInt() == 5 && r.get("remainingStaff").asInt() == 1) {
                satView = r;
            }
        }
        org.junit.jupiter.api.Assertions.assertNotNull(satView,
                "失败回执必须点名饱和承接区域; 实际 receivers=" + failNode.get("failure").get("receivers"));
        org.junit.jupiter.api.Assertions.assertTrue(
                failNode.get("failure").get("message").asText().contains("回灌落不成账"));

        // 5) 列表能同时查到成功单和失败单（关掉页面重开，失败不会变成功）
        mockMvc.perform(get("/api/closures?scenarioId=" + sid2))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].status").value("FAILED"));

        // 6) 作废成功单（回灌保留）
        mockMvc.perform(post("/api/closures/" + closureId + "/void")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"note\":\"值班长作废\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("VOIDED"));
        // 作废后承接区回灌仍在（50 流）
        org.junit.jupiter.api.Assertions.assertEquals(50,
                allocationRepository.findBaseAllocation(sid, recv.getId()).getAllocatedFlow());
        // 作废后可写回被封区域（不再 409）
        mockMvc.perform(put("/api/scenarios/" + sid + "/allocation")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"areaId\":" + closed.getId() + ",\"staff\":3,\"flow\":30}"))
                .andExpect(status().isOk());
    }

    @Test
    void unknownScenarioReturns404() throws Exception {
        StoreArea a = area("webx", 10, 10);
        mockMvc.perform(post("/api/closures")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(999999L, a.getId(), "x")))
                .andExpect(status().isNotFound());
    }
}
