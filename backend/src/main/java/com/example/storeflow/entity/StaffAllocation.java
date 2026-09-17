package com.example.storeflow.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "staff_allocation")
public class StaffAllocation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "scenario_id", nullable = false)
    private Long scenarioId;

    @Column(name = "area_id", nullable = false)
    private Long areaId;

    @Column(name = "allocated_staff", nullable = false)
    private Integer allocatedStaff;

    @Column(name = "allocated_flow", nullable = false)
    private Integer allocatedFlow;

    @Column(name = "is_optimized")
    private Boolean isOptimized = false;
}
