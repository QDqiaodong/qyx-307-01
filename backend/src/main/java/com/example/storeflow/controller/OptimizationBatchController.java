package com.example.storeflow.controller;

import com.example.storeflow.dto.BatchActionRequest;
import com.example.storeflow.dto.OptimizationBatchDTO;
import com.example.storeflow.service.OptimizationBatchService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 优化落地批次会签。
 *
 * <p>每一轮优化推演自动冻成一份批次（见 POST /api/scenarios/{id}/optimize 返回的
 * batchId/runSeq）。批次先由测算岗确认数字，再由现场经理签收；签收成功的那一刻，
 * 场景当前分配才在同一事务里切换成批次冻结的优化后方案。任何一步轮次对不上
 * （确认后又跑了新推演），旧批次即作废，签收返回 409 BATCH_STALE，并在报文里
 * 写明批次号、冻结轮次、场景当前轮次。
 */
@RestController
@RequestMapping("/api/batches")
@RequiredArgsConstructor
public class OptimizationBatchController {

    private final OptimizationBatchService batchService;

    /** 批次列表（可按场景过滤）：冻结轮次、会签进度、逐区域冻结快照、作废原因。 */
    @GetMapping
    public ResponseEntity<List<OptimizationBatchDTO>> list(@RequestParam(required = false) Long scenarioId) {
        return ResponseEntity.ok(batchService.list(scenarioId));
    }

    @GetMapping("/{id}")
    public ResponseEntity<OptimizationBatchDTO> getById(@PathVariable Long id) {
        return ResponseEntity.ok(batchService.getById(id));
    }

    /** 测算岗确认数字（只改批次状态，场景分配不动）。 */
    @PostMapping("/{id}/confirm")
    public ResponseEntity<OptimizationBatchDTO> confirm(@PathVariable Long id,
                                                        @RequestBody(required = false) BatchActionRequest body) {
        String operator = body != null ? body.getOperator() : null;
        return ResponseEntity.ok(batchService.confirm(id, operator));
    }

    /** 现场经理签收：会签闭环 + 场景切换成优化后方案，一笔事务一次做完。 */
    @PostMapping("/{id}/sign")
    public ResponseEntity<OptimizationBatchDTO> sign(@PathVariable Long id,
                                                     @RequestBody(required = false) BatchActionRequest body) {
        String operator = body != null ? body.getOperator() : null;
        return ResponseEntity.ok(batchService.sign(id, operator));
    }
}
