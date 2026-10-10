package com.codemind.integration.application.port;

/**
 * 代码托管平台调用失败。
 *
 * <p>{@code statusCode} 为平台返回的 HTTP 状态码；网络异常等非 HTTP 失败为 0。
 *
 * <p><b>message 里绝不能带 token</b>（全局约束：密钥不进日志、不进异常）。
 */
public class ScmException extends RuntimeException {

    private final int statusCode;

    public ScmException(String message, int statusCode) {
        super(message);
        this.statusCode = statusCode;
    }

    public ScmException(String message, int statusCode, Throwable cause) {
        super(message, cause);
        this.statusCode = statusCode;
    }

    /** 平台返回的 HTTP 状态码；0 表示非 HTTP 失败（网络、超时）。 */
    public int statusCode() {
        return statusCode;
    }
}
