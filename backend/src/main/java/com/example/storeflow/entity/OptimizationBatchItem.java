package com.example.storeflow.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

/**
 * 推演落地批次的逐区域冻结明细（不可变）。
 *
 * <p>每块区域一行，冻住推演当时的：
 * <ul>
 *   <li>核定容量 {@link #frozenMaxCapacity}、编制配额 {@link #frozenStaffQuota}；</li>
 *   <li>优化前分配 {@link #beforeStaff}/{@link #beforeFlow}；</li>
 *   <li>优化后分配 {@link #afterStaff}/{@link #afterFlow}。</li>
 * </ul>
 * 签收落账时把 {@link #afterStaff}/{@link #afterFlow} 原样写回该场景该区域的在用分配行，
 * 不再回头读取可能已被改动的区域额度——落地对的是冻住的档案，不是当下档案。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "optimization_batch_item",
        uniqueConstraints = @UniqueConstraint(name = "uk_opt_batch_area",
                columnNames = {"batch_id", "area_id"}))
public class OptimizationBatchItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "batch_id", nullable = false)
    private Long batchId;

    @Column(name = "scenario_id", nullable = false)
    private Long scenarioId;

    @Column(name = "area_id", nullable = false)
    private Long areaId;

    @Column(name = "area_name", length = 255)
    private String areaName;

    /** 推演当时该区域核定容量（冻住）。 */
    @Column(name = "frozen_max_capacity", nullable = false)
    private Integer frozenMaxCapacity;

    /** 推演当时该区域编制配额（冻住）。 */
    @Column(name = "frozen_staff_quota", nullable = false)
    private Integer frozenStaffQuota;

    /** 优化前在岗人员（冻住）。 */
    @Column(name = "before_staff", nullable = false)
    private Integer beforeStaff;

    /** 优化前在场客流（冻住）。 */
    @Column(name = "before_flow", nullable = false)
    private Integer beforeFlow;

    /** 优化后在岗人员（冻住；签收时写回场景的就是这一组）。 */
    @Column(name = "after_staff", nullable = false)
    private Integer afterStaff;

    /** 优化后在场客流（冻住；签收时写回场景的就是这一组）。 */
    @Column(name = "after_flow", nullable = false)
    private Integer afterFlow;
}
