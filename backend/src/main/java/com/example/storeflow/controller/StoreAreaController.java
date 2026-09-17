package com.example.storeflow.controller;

import com.example.storeflow.dto.AreaConfigDTO;
import com.example.storeflow.entity.StoreArea;
import com.example.storeflow.service.StoreAreaService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/areas")
@RequiredArgsConstructor
public class StoreAreaController {

    private final StoreAreaService storeAreaService;

    @GetMapping
    public ResponseEntity<List<StoreArea>> getAllAreas() {
        return ResponseEntity.ok(storeAreaService.getAllAreas());
    }

    @GetMapping("/{id}")
    public ResponseEntity<StoreArea> getAreaById(@PathVariable Long id) {
        return storeAreaService.getAreaById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping
    public ResponseEntity<StoreArea> createArea(@Valid @RequestBody AreaConfigDTO dto) {
        return ResponseEntity.ok(storeAreaService.createArea(dto));
    }

    @PutMapping("/{id}")
    public ResponseEntity<StoreArea> updateArea(@PathVariable Long id, @Valid @RequestBody AreaConfigDTO dto) {
        return ResponseEntity.ok(storeAreaService.updateArea(id, dto));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteArea(@PathVariable Long id) {
        storeAreaService.deleteArea(id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/risk")
    public ResponseEntity<List<StoreArea>> getRiskAreas() {
        return ResponseEntity.ok(storeAreaService.getRiskAreas());
    }
}
