package com.example.storeflow.entity;

/**
 * 优化落地批次状态（现场经理口径：两步会签都点头，场景才允许切成推演后方案）。
 *
 * <p>状态机：
 * <pre>
 *   推演冻结      -> PENDING_CONFIRM            （待测算岗确认；场景仍是旧方案）
 *   测算岗确认    -> PENDING_CONFIRM -> CONFIRMED（待现场经理签收；场景仍是旧方案）
 *   现场经理签收  -> CONFIRMED -> APPLIED        （终态；同事务把场景切成批次优化后方案）
 *   新一轮推演    -> PENDING_CONFIRM|CONFIRMED -> STALE（终态；测算确认一并作废，禁止再签收）
 * </pre>
 */
public enum BatchStatus {

    /** 待测算岗确认：推演刚冻结成批次，任何人不得据此改场景。 */
    PENDING_CONFIRM,

    /** 测算岗已确认、待现场经理签收：场景分配必须仍是旧方案。 */
    CONFIRMED,

    /** 会签完成已落地：场景当前分配已切换为本批次优化后方案。 */
    APPLIED,

    /** 已作废：确认（或待确认）期间又跑了新一轮推演，本批次冻结的轮次已过期。 */
    STALE;

    /** @return 是否仍处于会签流程中（可能被确认/签收，也可能被新一轮推演作废） */
    public boolean isOpen() {
        return this == PENDING_CONFIRM || this == CONFIRMED;
    }
}
