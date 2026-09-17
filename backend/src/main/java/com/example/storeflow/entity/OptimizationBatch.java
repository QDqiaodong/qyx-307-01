package com.example.storeflow.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.time.LocalDateTime;

/**
 * 优化落地批次（可复查的会签单）。
 *
 * <p>每一轮资源均衡推演在跑出来的同一事务里冻成一份批次：批次头记录第几轮推演、
 * 会签进度；逐区域快照（核定容量、编制配额、优化前分配、优化后分配）冻结在
 * {@link OptimizationBatchItem} 里，之后区域档案怎么改都不影响这份冻结数字。
 *
 * <p>只有测算岗确认、现场经理签收两步都完成（{@link BatchStatus#APPLIED}），
 * 场景当前分配才允许切换成批次里的优化后方案；切换与签收在同一事务内完成，
 * 绝不会出现「场景改了却没有批次对账」或「批次落地了场景还是旧方案」。
 *
 * <p>该表只追加/按状态流转，不允许物理删除；被新一轮推演取代的批次进入
 * {@link BatchStatus#STALE}，作废原因留痕，关掉页面再打开也不会复活。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "optimization_batch")
public class OptimizationBatch {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 绑定的客流场景。 */
    @Column(name = "scenario_id", nullable = false)
    private Long scenarioId;

    @Column(name = "scenario_name", nullable = false)
    private String scenarioName;

    /** 本批次冻结的是该场景的第几轮推演（对应 FlowScenario.currentRunSeq）。 */
    @Column(name = "run_seq", nullable = false)
    private Integer runSeq;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private BatchStatus status;

    // ===== 会签留痕 =====

    /** 测算岗确认人。 */
    @Column(name = "confirmed_by")
    private String confirmedBy;

    @Column(name = "confirmed_at")
    private LocalDateTime confirmedAt;

    /** 现场经理签收人。 */
    @Column(name = "signed_by")
    private String signedBy;

    @Column(name = "signed_at")
    private LocalDateTime signedAt;

    /** 落地时间（场景切换到优化后方案的时刻，与签收同一事务）。 */
    @Column(name = "applied_at")
    private LocalDateTime appliedAt;

    /** 作废原因（仅 STALE）：写明被第几轮新推演取代。 */
    @Column(name = "stale_reason", length = 500)
    private String staleReason;

    // ===== 冻结时的整体效果数字（便于列表复查，明细在批次项里） =====

    @Column(name = "before_max_saturation")
    private Double beforeMaxSaturation;

    @Column(name = "after_max_saturation")
    private Double afterMaxSaturation;

    @Column(name = "before_overloaded_count")
    private Integer beforeOverloadedCount;

    @Column(name = "after_overloaded_count")
    private Integer afterOverloadedCount;

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
