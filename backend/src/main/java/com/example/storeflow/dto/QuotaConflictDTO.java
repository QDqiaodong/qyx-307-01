package com.example.storeflow.dto;

import lombok.Data;

import java.util.List;

/**
 * 额度冲突明细：承接区域的核定容量或编制配额被改小，已生效封区单的回灌数字超额。
 *
 * <p>回灌数字（台账）不偷偷改写去迁就新额度；该封区单进入「额度冲突、禁止解封」，
 * 解封按钮不可用，直到值班长手工作废该单。
 */
@Data
public class QuotaConflictDTO {

    /** 发生冲突的承接区域视图，可有多块。 */
    private List<ConflictArea> conflicts;

    private String message;

    @Data
    public static class ConflictArea {
        private Long receiverAreaId;
        private String receiverAreaName;

        /** 本封区单回灌到该承接区域的人员（台账原值，不改）。 */
        private Integer injectedStaff;
        /** 本封区单回灌到该承接区域的客流（台账原值，不改）。 */
        private Integer injectedFlow;

        /** 改小后的编制配额。 */
        private Integer newStaffQuota;
        /** 改小后的核定容量。 */
        private Integer newMaxCapacity;

        /** 超出新编制配额多少（&gt;0 才冲突；null/0 表示该维度未冲突）。 */
        private Integer staffOverflow;
        /** 超出新容量多少（&gt;0 才冲突；null/0 表示该维度未冲突）。 */
        private Integer flowOverflow;
    }
}
