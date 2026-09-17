package com.example.storeflow.exception;

/**
 * 业务规则冲突（HTTP 409）：例如对仍生效封区区域手工写回人员/客流、重复封区、
 * 禁止解封时解封、解封回撤回灌时当前场景已放不下等。
 */
public class BusinessConflictException extends RuntimeException {

    /** 机器可读错误码，前端可据此区分提示。 */
    private final String code;

    public BusinessConflictException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
