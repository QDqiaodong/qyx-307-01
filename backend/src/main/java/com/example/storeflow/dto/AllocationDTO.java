package com.example.storeflow.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Data;

@Data
public class AllocationDTO {

    private Long id;

    @NotNull(message = "区域ID不能为空")
    @Positive(message = "区域ID必须为正数")
    private Long areaId;

    @NotNull(message = "分配人员数不能为空")
    @Positive(message = "分配人员数必须为正数")
    private Integer allocatedStaff;

    @NotNull(message = "分配客流不能为空")
    @Positive(message = "分配客流必须为正数")
    private Integer allocatedFlow;

    private String areaName;

    private Integer maxCapacity;

    private Double saturationRate;

    private Boolean isOverloaded;
}
