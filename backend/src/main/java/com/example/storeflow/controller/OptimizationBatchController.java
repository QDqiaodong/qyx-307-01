package com.example.storeflow.controller;

import com.example.storeflow.dto.BatchSignRequest;
import com.example.storeflow.dto.OptimizationBatchDTO;
import com.example.storeflow.service.OptimizationBatchService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 推演落地批次会签。
 *
 * <p>推演时自动冻结批次；测算岗 {@code confirm}、现场经理 {@code sign} 两步闸门。
 * 只有签收成功（LANDED）的同一事务，场景在用分配才改成批次冻住的优化后方案。
 * 旧批次（又跑过新一轮推演）确认/签收一律 409，回执点名批次号、冻的轮次、场景当前轮次。
 */
@RestController
@RequestMapping("/api/batches")
@RequiredArgsConstructor
public class OptimizationBatchController {

    private final OptimizationBatchService batchService;

    /** 批次台账，可按场景过滤；看每份批次冻的哪轮、会签到哪步、是否已落地/作废。 */
    @GetMapping
    public ResponseEntity<List<OptimizationBatchDTO>> list(@RequestParam(required = false) Long scenarioId) {
        return ResponseEntity.ok(batchService.list(scenarioId));
    }

    @GetMapping("/{id}")
    public ResponseEntity<OptimizationBatchDTO> getById(@PathVariable Long id) {
        return ResponseEntity.ok(batchService.getById(id));
    }

    /** 测算岗确认数字（第一步）。只改批次状态，场景分配仍是旧方案。 */
    @PostMapping("/{id}/confirm")
    public ResponseEntity<OptimizationBatchDTO> confirm(@PathVariable Long id,
                                                        @RequestBody(required = false) BatchSignRequest request) {
        String note = request != null ? request.getNote() : null;
        return ResponseEntity.ok(batchService.analystConfirm(id, note));
    }

    /** 现场经理签收（第二步）：同事务把场景在用分配改成优化后方案。 */
    @PostMapping("/{id}/sign")
    public ResponseEntity<OptimizationBatchDTO> sign(@PathVariable Long id,
                                                     @RequestBody(required = false) BatchSignRequest request) {
        String note = request != null ? request.getNote() : null;
        return ResponseEntity.ok(batchService.managerSign(id, note));
    }
}
