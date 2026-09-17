package com.example.storeflow.dto;

import lombok.Data;

import java.util.List;

@Data
public class OptimizationResultDTO {

    private Long scenarioId;

    private String scenarioName;

    /** 本轮是该场景第几轮推演（落地批次冻住的就是这个轮次）。 */
    private Integer optimizationRound;

    private List<AllocationDTO> beforeAllocations;

    private List<AllocationDTO> afterAllocations;

    private Double beforeMaxSaturation;

    private Double afterMaxSaturation;

    private Double beforeAvgSaturation;

    private Double afterAvgSaturation;

    private Integer beforeOverloadedCount;

    private Integer afterOverloadedCount;

    /** 本轮推演冻出的落地批次 id（前端会签面板据此打开批次）。 */
    private Long batchId;

    private List<OptimizationStepDTO> optimizationSteps;
}
