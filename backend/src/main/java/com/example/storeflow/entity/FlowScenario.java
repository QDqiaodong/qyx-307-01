package com.example.storeflow.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "flow_scenario")
public class FlowScenario {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "scenario_name", nullable = false)
    private String scenarioName;

    @Column(name = "festival_name", nullable = false)
    private String festivalName;

    @Column(name = "estimated_total_flow", nullable = false)
    private Integer estimatedTotalFlow;

    /**
     * 当前已跑到第几轮优化推演（每执行一次推演 +1）。
     * 落地批次冻结的就是某一轮；批次轮次落后于本字段即作废。
     */
    @Column(name = "current_run_seq")
    private Integer currentRunSeq = 0;

    /**
     * 场景当前分配对应哪一轮推演的优化后方案（由会签完成的落地批次写回）；
     * null 表示仍是手工分配方案，尚未有任何批次落地。
     */
    @Column(name = "applied_run_seq")
    private Integer appliedRunSeq;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}
