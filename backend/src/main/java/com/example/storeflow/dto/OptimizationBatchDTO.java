package com.example.storeflow.dto;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 推演落地批次视图：批次头概览 + 每块区域冻结档案 + 会签状态。
 * 列表与详情共用；前端据此渲染「测算确认 / 现场签收」闸门与冻住的对账数字。
 */
@Data
public class OptimizationBatchDTO {

    private Long id;

    private Long scenarioId;

    private String scenarioName;

    /** 这份批次冻住的推演轮次。 */
    private Integer optimizationRound;

    /** 场景当前已执行到的推演轮次（与冻住轮次不一致即为旧批次）。 */
    private Integer currentRound;

    private String status;

    private String statusText;

    /** 是否还在会签流程中（DRAFT / CONFIRMED）。 */
    private Boolean live;

    /** 冻住的轮次是否仍是场景最新一轮（false = 已被新一轮推演作废，禁止确认/签收）。 */
    private Boolean current;

    private Boolean canAnalystConfirm;

    private Boolean canManagerSign;

    // ===== 推演概览冻结 =====

    private Double beforeMaxSaturation;
    private Double afterMaxSaturation;
    private Double beforeAvgSaturation;
    private Double afterAvgSaturation;
    private Integer beforeOverloadedCount;
    private Integer afterOverloadedCount;
    private Integer beforeTotalStaff;
    private Integer beforeTotalFlow;
    private Integer afterTotalStaff;
    private Integer afterTotalFlow;

    /** 每块区域的核定容量、编制配额、优化前/优化后分配（冻住）。 */
    private List<BatchItemDTO> items;

    // ===== 会签 =====

    private LocalDateTime analystConfirmedAt;
    private LocalDateTime managerSignedAt;
    private LocalDateTime landedAt;
    private String analystNote;
    private String managerNote;

    /** 作废说明（新一轮推演轮次、本批冻住轮次）。 */
    private String supersedeReason;

    /** 已被作废时的结构化失败回执。 */
    private BatchFailureDTO failure;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
