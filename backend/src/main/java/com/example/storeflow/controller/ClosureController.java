package com.example.storeflow.controller;

import com.example.storeflow.dto.AreaClosureDTO;
import com.example.storeflow.dto.ClosureSubmitRequest;
import com.example.storeflow.service.ClosureService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 临时封区回灌。
 *
 * <p>提交结果永远 200 返回一张封区单：成功为 EFFECTIVE，失败为 FAILED（回执里写清卡在哪块
 * 承接区域、还差多少容量/编制）。这样“关掉页面再打开，失败单不会自己变成成功”。
 */
@RestController
@RequestMapping("/api/closures")
@RequiredArgsConstructor
public class ClosureController {

    private final ClosureService closureService;

    /** 提交封区（选区域 + 绑场景 + 原因）。原子：清被封区域 + 回灌开放区域。 */
    @PostMapping
    public ResponseEntity<AreaClosureDTO> submit(@Valid @RequestBody ClosureSubmitRequest request) {
        return ResponseEntity.ok(closureService.submitClosure(request));
    }

    /** 封区单列表，可按场景过滤；看封区是否仍生效、回灌落到哪些开放区域、失败/冲突回执。 */
    @GetMapping
    public ResponseEntity<List<AreaClosureDTO>> list(@RequestParam(required = false) Long scenarioId) {
        return ResponseEntity.ok(closureService.list(scenarioId));
    }

    @GetMapping("/{id}")
    public ResponseEntity<AreaClosureDTO> getById(@PathVariable Long id) {
        return ResponseEntity.ok(closureService.getById(id));
    }

    /** 值班长手工作废：区域恢复可营业，回灌不撤回。 */
    @PostMapping("/{id}/void")
    public ResponseEntity<AreaClosureDTO> voidClosure(@PathVariable Long id,
                                                      @RequestBody(required = false) Map<String, String> body) {
        String note = body != null ? body.get("note") : null;
        return ResponseEntity.ok(closureService.voidClosure(id, note));
    }

    /** 正常解封（额度冲突时后端拒绝，按钮不可用）。 */
    @PostMapping("/{id}/reopen")
    public ResponseEntity<AreaClosureDTO> reopenClosure(@PathVariable Long id,
                                                        @RequestBody(required = false) Map<String, String> body) {
        String note = body != null ? body.get("note") : null;
        return ResponseEntity.ok(closureService.reopenClosure(id, note));
    }
}
