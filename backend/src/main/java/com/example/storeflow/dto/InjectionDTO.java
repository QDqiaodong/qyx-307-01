package com.example.storeflow.dto;

import lombok.Data;

/**
 * 回灌台账明细响应：封区单回灌落到了哪块开放区域、落了多少、当时还剩多少额度。
 */
@Data
public class InjectionDTO {

    private Long id;
    private Long closureId;
    private Long scenarioId;

    private Long receiverAreaId;
    private String receiverAreaName;

    private Integer injectedStaff;
    private Integer injectedFlow;

    private Integer staffBefore;
    private Integer flowBefore;

    private Integer remainingStaffAtTime;
    private Integer remainingFlowAtTime;
}
