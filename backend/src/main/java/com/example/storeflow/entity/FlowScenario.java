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
     * 当前已执行到的优化推演轮次（从 0 开始，每执行一次推演 +1）。
     * 落地批次冻住其中某一轮；新一轮推演会把上一轮在途批次作废。
     */
    @Column(name = "optimization_round", nullable = false)
    private Integer optimizationRound = 0;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}
