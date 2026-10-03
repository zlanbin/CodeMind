package com.codemind.integration.application;

/**
 * Webhook 验签失败。interfaces 层把它映射成 401。
 */
public class InvalidSignatureException extends RuntimeException {

    public InvalidSignatureException(String message) {
        super(message);
    }
}
