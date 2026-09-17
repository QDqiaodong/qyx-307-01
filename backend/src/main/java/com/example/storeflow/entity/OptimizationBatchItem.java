package com.example.storeflow.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

/**
 * 优化落地批次的逐区域冻结快照（不可变明细）。
 *
 * <p>每一行把「推演当时」一块区域的四个档案数字钉死：核定容量、编制配额、
 * 优化前分配（人员/客流）、优化后分配（人员/客流）。之后区域档案被改、
 * 场景又跑了新推演，都不回写这里的数字——复查时对的就是这一份账。
 *
 * <p>现场经理签收落地时，场景当前分配按本表的 after 列逐区域写回。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "optimization_batch_item")
public class OptimizationBatchItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "batch_id", nullable = false)
    private Long batchId;

    @Column(name = "area_id", nullable = false)
    private Long areaId;

    @Column(name = "area_name", nullable = false)
    private String areaName;

    /** 推演当时该区域的核定容量（冻结，不随区域档案后续调整而变化）。 */
    @Column(name = "max_capacity", nullable = false)
    private Integer maxCapacity;

    /** 推演当时该区域的编制配额（冻结）。 */
    @Column(name = "staff_quota", nullable = false)
    private Integer staffQuota;

    /** 优化前分配人员。 */
    @Column(name = "before_staff", nullable = false)
    private Integer beforeStaff;

    /** 优化前分配客流。 */
    @Column(name = "before_flow", nullable = false)
    private Integer beforeFlow;

    /** 优化后分配人员（签收落地时写回场景）。 */
    @Column(name = "after_staff", nullable = false)
    private Integer afterStaff;

    /** 优化后分配客流（签收落地时写回场景）。 */
    @Column(name = "after_flow", nullable = false)
    private Integer afterFlow;
}
