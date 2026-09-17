package com.example.storeflow.controller;

import com.example.storeflow.dto.AllocationDTO;
import com.example.storeflow.dto.AreaLoadDTO;
import com.example.storeflow.dto.FlowScenarioDTO;
import com.example.storeflow.dto.OptimizationResultDTO;
import com.example.storeflow.entity.FlowScenario;
import com.example.storeflow.service.FlowScenarioService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/scenarios")
@RequiredArgsConstructor
public class FlowScenarioController {

    private final FlowScenarioService flowScenarioService;

    @GetMapping
    public ResponseEntity<List<FlowScenario>> getAllScenarios() {
        return ResponseEntity.ok(flowScenarioService.getAllScenarios());
    }

    @GetMapping("/{id}")
    public ResponseEntity<FlowScenario> getScenarioById(@PathVariable Long id) {
        return ResponseEntity.ok(flowScenarioService.getScenarioById(id));
    }

    @PostMapping
    public ResponseEntity<FlowScenario> createScenario(@Valid @RequestBody FlowScenarioDTO dto) {
        return ResponseEntity.ok(flowScenarioService.createScenario(dto));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteScenario(@PathVariable Long id) {
        flowScenarioService.deleteScenario(id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{id}/load")
    public ResponseEntity<List<AreaLoadDTO>> calculateLoad(@PathVariable Long id) {
        return ResponseEntity.ok(flowScenarioService.calculateLoad(id));
    }

    @PostMapping("/{id}/optimize")
    public ResponseEntity<OptimizationResultDTO> optimize(@PathVariable Long id) {
        return ResponseEntity.ok(flowScenarioService.optimize(id));
    }

    @GetMapping("/{id}/optimized")
    public ResponseEntity<List<AllocationDTO>> getOptimizedAllocations(@PathVariable Long id) {
        return ResponseEntity.ok(flowScenarioService.getOptimizedAllocations(id));
    }

    @PutMapping("/{id}/allocation")
    public ResponseEntity<Void> updateAllocation(
            @PathVariable Long id,
            @RequestBody Map<String, Object> request) {
        Long areaId = Long.parseLong(request.get("areaId").toString());
        Integer staff = Integer.parseInt(request.get("staff").toString());
        Integer flow = Integer.parseInt(request.get("flow").toString());
        flowScenarioService.updateAllocation(id, areaId, staff, flow);
        return ResponseEntity.ok().build();
    }
}
