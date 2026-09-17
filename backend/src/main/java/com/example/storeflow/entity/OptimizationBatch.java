package com.example.storeflow.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.time.LocalDateTime;

/**
 * 推演落地批次：把一轮资源均衡推演冻成一份可复查、需两段会签的落地档案。
 *
 * <p>一条批次严格绑定「一个客流场景 + 一轮推演（{@link #optimizationRound}）」。
 * 冻结即不可变：批次头冻住该轮推演当时的饱和度/过载概览与优化前后总量，
 * 每块区域的核定容量、编制配额、优化前分配、优化后分配冻在
 * {@link OptimizationBatchItem} 明细里；区域额度后来被改小也不改写批次数字。
 *
 * <p>会签闸门：测算岗 {@link #analystConfirmedAt} 确认、现场经理
 * {@link #managerSignedAt} 签收，两步都点头（{@link BatchStatus#LANDED}）的同一笔事务
 * 才把场景在用分配改成优化后方案。CONFIRMED 但未签收期间，场景分配必须仍是旧方案。
 *
 * <p>该表只追加/按状态流转，不允许物理删除；新一轮推演把上一轮在途批次置为
 * {@link BatchStatus#SUPERSEDED}，旧批次永久保留可对账。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "optimization_batch",
        uniqueConstraints = @UniqueConstraint(name = "uk_opt_batch_round",
                columnNames = {"scenario_id", "optimization_round"}))
public class OptimizationBatch {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 绑定的客流场景。 */
    @Column(name = "scenario_id", nullable = false)
    private Long scenarioId;

    /**
     * 冻结的是第几轮推演。每执行一次优化推演轮次 +1；批次用它判断自己冻的是哪一轮、
     * 场景现在又是哪一轮（轮次不一致即已被新一轮推演作废）。
     */
    @Column(name = "optimization_round", nullable = false)
    private Integer optimizationRound;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private BatchStatus status;

    // ===== 该轮推演概览快照（冻结，不随后续区域/场景变化改写） =====

    @Column(name = "scenario_name", length = 255)
    private String scenarioName;

    @Column(name = "before_max_saturation")
    private Double beforeMaxSaturation;

    @Column(name = "after_max_saturation")
    private Double afterMaxSaturation;

    @Column(name = "before_avg_saturation")
    private Double beforeAvgSaturation;

    @Column(name = "after_avg_saturation")
    private Double afterAvgSaturation;

    @Column(name = "before_overloaded_count")
    private Integer beforeOverloadedCount;

    @Column(name = "after_overloaded_count")
    private Integer afterOverloadedCount;

    /** 推演当时优化前分配的人员/客流总量（用于复查总量守恒）。 */
    @Column(name = "before_total_staff")
    private Integer beforeTotalStaff;

    @Column(name = "before_total_flow")
    private Integer beforeTotalFlow;

    /** 批次冻住的优化后方案人员/客流总量。 */
    @Column(name = "after_total_staff")
    private Integer afterTotalStaff;

    @Column(name = "after_total_flow")
    private Integer afterTotalFlow;

    // ===== 两段会签 =====

    /** 测算岗确认时间；null 表示测算岗尚未点头。 */
    @Column(name = "analyst_confirmed_at")
    private LocalDateTime analystConfirmedAt;

    /** 现场经理签收时间；null 表示现场经理尚未点头（此时场景仍必须是旧方案）。 */
    @Column(name = "manager_signed_at")
    private LocalDateTime managerSignedAt;

    @Column(name = "analyst_note", length = 500)
    private String analystNote;

    @Column(name = "manager_note", length = 500)
    private String managerNote;

    /**
     * 作废原因（SUPERSEDED）：说明场景上现在已是第几轮推演，本批次冻的是第几轮。
     * 失败回执必须能让人看清是哪份批次、冻的哪一轮、场景现在又是哪一轮。
     */
    @Column(name = "supersede_reason", length = 500)
    private String supersedeReason;

    /** 会签落地时间：只有该字段非空，场景分配才被允许改成优化后方案。 */
    @Column(name = "landed_at")
    private LocalDateTime landedAt;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
