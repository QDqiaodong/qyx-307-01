package com.example.storeflow.dto;

import lombok.Data;

@Data
public class AreaLoadDTO {

    private Long areaId;

    private String areaName;

    private Integer currentFlow;

    private Integer maxCapacity;

    private Integer allocatedStaff;

    private Integer staffQuota;

    private Double saturationRate;

    private Double staffLoadRate;

    private Boolean isOverloaded;

    private Integer riskLevel;
}
