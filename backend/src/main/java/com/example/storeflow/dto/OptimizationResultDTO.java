package com.example.storeflow.dto;

import lombok.Data;

import java.util.List;

@Data
public class OptimizationResultDTO {

    private Long scenarioId;

    private String scenarioName;

    /** 本轮是第几轮推演（随场景单调递增）。 */
    private Integer runSeq;

    /** 本轮推演冻结出的落地批次 id（待会签，签收前场景分配不变）。 */
    private Long batchId;

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
