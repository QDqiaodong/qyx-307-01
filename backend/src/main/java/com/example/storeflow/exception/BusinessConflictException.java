package com.example.storeflow.exception;

/**
 * 业务规则冲突（HTTP 409）：例如对仍生效封区区域手工写回人员/客流、重复封区、
 * 禁止解封时解封、解封回撤回灌时当前场景已放不下、拿已被新一轮推演作废的旧批次签收等。
 */
public class BusinessConflictException extends RuntimeException {

    /** 机器可读错误码，前端可据此区分提示。 */
    private final String code;

    /** 可选的结构化回执（如批次作废回执：哪份批次、冻的哪轮、场景现在哪轮）。 */
    private final transient Object data;

    public BusinessConflictException(String code, String message) {
        this(code, message, null);
    }

    public BusinessConflictException(String code, String message, Object data) {
        super(message);
        this.code = code;
        this.data = data;
    }

    public String getCode() {
        return code;
    }

    public Object getData() {
        return data;
    }
}
