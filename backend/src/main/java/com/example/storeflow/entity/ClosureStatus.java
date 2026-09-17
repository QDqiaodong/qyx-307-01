package com.example.storeflow.entity;

/**
 * 临时封区单状态。
 *
 * <p>状态机：
 * <pre>
 *   提交成功  -> EFFECTIVE
 *   提交失败  -> FAILED                 （终态，被封区域保持可营业，旧分配不动）
 *   额度改小  -> EFFECTIVE -> QUOTA_CONFLICT（终态视角：禁止解封，只能作废）
 *   正常解封  -> EFFECTIVE -> REOPENED  （终态，回灌撤回、区域恢复营业）
 *   值班长作废 -> EFFECTIVE|QUOTA_CONFLICT -> VOIDED（终态，区域恢复营业，回灌不撤回）
 * </pre>
 */
public enum ClosureStatus {

    /** 封区生效中：被封区域已清 0，回灌已落账，区域不可作为调入目标、不可手工写回。 */
    EFFECTIVE,

    /** 提交失败：没有任何开放区域能完整吃下回灌，被封区域保持可营业，旧分配一行未动。 */
    FAILED,

    /** 额度冲突：承接区域核定容量/编制被改小，原回灌数字超额；禁止解封，只能手工作废。 */
    QUOTA_CONFLICT,

    /** 已解封：生效单正常解封，回灌已撤回，被封区域恢复可营业。 */
    REOPENED,

    /** 已作废：值班长手工作废，被封区域恢复可营业，但回灌到承接区域的人员/客流不撤回。 */
    VOIDED;

    /**
     * @return 该状态是否仍代表「封区仍生效」（封锁调入目标 / 手工写回 / 承接占量仍成立）
     */
    public boolean isLive() {
        return this == EFFECTIVE || this == QUOTA_CONFLICT;
    }
}
