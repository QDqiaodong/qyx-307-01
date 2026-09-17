package com.example.storeflow.dto;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 封区单响应：含是否仍生效、状态文案、回灌落到哪些开放区域、失败回执、额度冲突。
 */
@Data
public class AreaClosureDTO {

    private Long id;
    private Long scenarioId;
    private String scenarioName;

    private Long areaId;
    private String areaName;

    private String reason;

    /** EFFECTIVE / FAILED / QUOTA_CONFLICT / REOPENED / VOIDED */
    private String status;
    private String statusText;

    /** 封区是否仍生效（EFFECTIVE 或 QUOTA_CONFLICT）。 */
    private Boolean live;
    /** 是否禁止解封（额度冲突时为 true，解封按钮不可用，只能作废）。 */
    private Boolean unsealBlocked;

    private Integer evacuatedStaff;
    private Integer evacuatedFlow;
    private Integer injectedStaff;
    private Integer injectedFlow;

    /** 失败回执（仅 FAILED 有值）。 */
    private ClosureFailureDTO failure;

    /** 额度冲突明细（仅 QUOTA_CONFLICT 有值）。 */
    private QuotaConflictDTO quotaConflict;

    /** 回灌落到了哪些开放区域。 */
    private List<InjectionDTO> injections;

    private String terminalNote;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
