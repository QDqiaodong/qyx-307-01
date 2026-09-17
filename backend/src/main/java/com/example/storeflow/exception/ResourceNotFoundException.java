package com.example.storeflow.exception;

/**
 * 资源不存在（HTTP 404）：场景 / 区域 / 封区单不存在。
 */
public class ResourceNotFoundException extends RuntimeException {
    public ResourceNotFoundException(String message) {
        super(message);
    }
}
