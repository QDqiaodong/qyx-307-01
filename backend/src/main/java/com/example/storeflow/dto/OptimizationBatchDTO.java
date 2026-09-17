package com.example.storeflow.dto;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 优化落地批次（会签单）视图：冻结轮次、会签进度、逐区域冻结快照，
 * 以及场景当前轮次上下文（便于前端直接展示「冻的是哪一轮 / 场景现在哪一轮」）。
 */
@Data
public class OptimizationBatchDTO {

    private Long id;

    private Long scenarioId;

    private String scenarioName;

    /** 本批次冻结的是第几轮推演。 */
    private Integer runSeq;

    private String status;

    private String statusText;

    private String confirmedBy;

    private LocalDateTime confirmedAt;

    private String signedBy;

    private LocalDateTime signedAt;

    private LocalDateTime appliedAt;

    private String staleReason;

    private Double beforeMaxSaturation;

    private Double afterMaxSaturation;

    private Integer beforeOverloadedCount;

    private Integer afterOverloadedCount;

    private List<OptimizationBatchItemDTO> items;

    /** 场景当前已跑到第几轮推演。 */
    private Integer scenarioCurrentRunSeq;

    /** 场景当前分配落地的是第几轮推演方案（null = 仍是手工方案）。 */
    private Integer scenarioAppliedRunSeq;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
