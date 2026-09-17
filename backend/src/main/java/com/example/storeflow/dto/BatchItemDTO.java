package com.example.storeflow.dto;

import lombok.Data;

/**
 * 落地批次里一块区域的冻结档案行：核定容量、编制配额、优化前/优化后分配都取自推演当时。
 */
@Data
public class BatchItemDTO {

    private Long id;

    private Long areaId;

    private String areaName;

    /** 推演当时核定容量。 */
    private Integer frozenMaxCapacity;

    /** 推演当时编制配额。 */
    private Integer frozenStaffQuota;

    private Integer beforeStaff;

    private Integer beforeFlow;

    private Integer afterStaff;

    private Integer afterFlow;

    /** 优化前饱和度（按冻住的容量现算，仅用于展示复查）。 */
    private Double beforeSaturationRate;

    /** 优化后饱和度（按冻住的容量现算，仅用于展示复查）。 */
    private Double afterSaturationRate;

    private Boolean beforeOverloaded;

    private Boolean afterOverloaded;
}
