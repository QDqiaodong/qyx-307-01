package com.example.storeflow.dto;

import lombok.Data;

/**
 * 落地批次的逐区域冻结快照（推演当时的核定容量/编制配额/优化前后分配）。
 */
@Data
public class OptimizationBatchItemDTO {

    private Long areaId;

    private String areaName;

    /** 冻结的核定容量。 */
    private Integer maxCapacity;

    /** 冻结的编制配额。 */
    private Integer staffQuota;

    private Integer beforeStaff;

    private Integer beforeFlow;

    private Integer afterStaff;

    private Integer afterFlow;

    /** 按冻结容量计算的优化前饱和度（%）。 */
    private Double beforeSaturation;

    /** 按冻结容量计算的优化后饱和度（%）。 */
    private Double afterSaturation;
}
