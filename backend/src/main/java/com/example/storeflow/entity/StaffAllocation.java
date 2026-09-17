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

    /**
     * false = 场景当前在用分配（排人依据）；true = 某轮推演的优化后草稿。
     * 草稿永远不会直接覆盖在用分配，只有批次会签 LANDED 后才由冻住的优化后方案写回。
     */
    @Column(name = "is_optimized")
    private Boolean isOptimized = false;

    /** 草稿行所属推演轮次；在用分配行为 null。 */
    @Column(name = "optimization_round")
    private Integer optimizationRound;

    // ===== 推演草稿行随轮冻住的快照（在用分配行为 null），供冻结批次原样读取 =====

    @Column(name = "before_staff")
    private Integer beforeStaff;

    @Column(name = "before_flow")
    private Integer beforeFlow;

    @Column(name = "frozen_max_capacity")
    private Integer frozenMaxCapacity;

    @Column(name = "frozen_staff_quota")
    private Integer frozenStaffQuota;
}
