package com.example.storeflow.dto;

import lombok.Data;

import java.util.List;

/**
 * 封区失败回执：让现场看到整单为什么落不成账。
 *
 * <p>安全岗口径——回灌落不成账就绝不能关区。只要有任何一块承接区域会超容量或超编制，
 * 整单失败，被封区域保持可营业、旧分配一行不动。这里给出：
 * <ul>
 *   <li>总缺口：全部开放区域加起来还差多少人员/客流才能吃下这次回灌；</li>
 *   <li>逐块承接区域的剩余额度、能吃多少、还差多少；</li>
 *   <li>哪些剩余量是被别的封区单占掉的（并发冲突时点名）。</li>
 * </ul>
 */
@Data
public class ClosureFailureDTO {

    private Long scenarioId;

    private Long closedAreaId;

    private String closedAreaName;

    /** 需要疏散回灌的人员总数。 */
    private Integer evacuatedStaff;

    /** 需要疏散回灌的客流总数。 */
    private Integer evacuatedFlow;

    /** 所有开放区域剩余编制之和。 */
    private Integer totalRemainingStaff;

    /** 所有开放区域剩余容量之和。 */
    private Integer totalRemainingFlow;

    /** 总体还差多少人员配额（&gt;0 表示承接不下）。 */
    private Integer shortStaff;

    /** 总体还差多少容量（&gt;0 表示承接不下）。 */
    private Integer shortFlow;

    /** 是否没有任何仍开放区域（整块场景只剩被封区域）。 */
    private Boolean noOpenArea;

    /** 逐块开放承接区域的承接能力快照。 */
    private List<ReceiverCapacityView> receivers;

    /** 现场可读的失败说明。 */
    private String message;

    /**
     * 一块开放承接区域在提交时刻的承接能力。
     */
    @Data
    public static class ReceiverCapacityView {
        private Long areaId;
        private String areaName;

        /** 当前已占用人员（含之前别的封区单回灌）。 */
        private Integer staffBefore;
        /** 当前已占用客流（含之前别的封区单回灌）。 */
        private Integer flowBefore;

        private Integer staffQuota;
        private Integer maxCapacity;

        private Integer remainingStaff;
        private Integer remainingFlow;

        /**
         * 这块区域的剩余量是被哪些仍生效封区单回灌占掉的（并发冲突点名用）。
         * 每个元素为占量封区单ID。
         */
        private List<Long> occupiedByClosureIds;
    }
}
