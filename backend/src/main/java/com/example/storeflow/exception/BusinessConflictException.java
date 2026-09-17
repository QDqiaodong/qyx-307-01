package com.example.storeflow.exception;

import java.util.Map;

/**
 * 业务规则冲突（HTTP 409）：例如对仍生效封区区域手工写回人员/客流、重复封区、
 * 禁止解封时解封、解封回撤回灌时当前场景已放不下、落地批次未会签/已作废仍要改场景等。
 */
public class BusinessConflictException extends RuntimeException {

    /** 机器可读错误码，前端可据此区分提示。 */
    private final String code;

    /**
     * 结构化明细（可空）：例如批次落地被拒时给出 batchId / frozenRunSeq / currentRunSeq，
     * 让前端不必解析中文文案也能展示「哪份批次、冻的哪一轮、场景现在哪一轮」。
     */
    private final Map<String, Object> details;

    public BusinessConflictException(String code, String message) {
        this(code, message, null);
    }

    public BusinessConflictException(String code, String message, Map<String, Object> details) {
        super(message);
        this.code = code;
        this.details = details;
    }

    public String getCode() {
        return code;
    }

    public Map<String, Object> getDetails() {
        return details;
    }
}
