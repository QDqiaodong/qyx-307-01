package com.example.storeflow.dto;

import lombok.Data;

import java.util.List;

@Data
public class OptimizationResultDTO {

    private Long scenarioId;

    private String scenarioName;

    private List<AllocationDTO> beforeAllocations;

    private List<AllocationDTO> afterAllocations;

    private Double beforeMaxSaturation;

    private Double afterMaxSaturation;

    private Double beforeAvgSaturation;

    private Double afterAvgSaturation;

    private Integer beforeOverloadedCount;

    private Integer afterOverloadedCount;

    private List<OptimizationStepDTO> optimizationSteps;
}
