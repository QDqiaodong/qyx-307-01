package com.example.storeflow.entity;

/**
 * 推演落地批次状态。
 *
 * <p>会签是两步闸门：测算岗确认（{@link #DRAFT} -> {@link #CONFIRMED}）、
 * 现场经理签收（{@link #CONFIRMED} -> {@link #LANDED}）。只有走到 {@link #LANDED}
 * 的同一笔事务里，才允许把场景在用分配改成批次冻住的优化后方案。
 *
 * <p>批次冻的是「某一轮推演」。场景一旦又跑了新一轮推演，上一轮所有未走完会签的批次
 * （DRAFT / CONFIRMED）一律 {@link #SUPERSEDED} 作废，现场经理不能再拿旧批次改场景。
 */
public enum BatchStatus {

    /** 批次已冻结档案，等待测算岗确认数字。 */
    DRAFT,

    /** 测算岗已确认，等待现场经理签收；签收前场景分配必须仍是旧方案。 */
    CONFIRMED,

    /** 场景又跑了新一轮推演，本批次冻的轮次已过时，确认作废、禁止签收。 */
    SUPERSEDED,

    /** 两步会签完成，批次里的优化后方案已在同一事务写回场景在用分配（终态）。 */
    LANDED;

    /** 是否仍在会签流程中（可被新一轮推演作废）。 */
    public boolean isLive() {
        return this == DRAFT || this == CONFIRMED;
    }
}
