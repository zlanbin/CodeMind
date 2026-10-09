package com.codemind.review.domain.agent;

/**
 * LLM 调用失败（网络、鉴权、限流、服务端错误）。
 *
 * <p>与「输出不合法」区分：输出不合法由 {@link FindingParseException} 表示。
 */
public class LlmException extends Exception {

    public LlmException(String message) {
        super(message);
    }

    public LlmException(String message, Throwable cause) {
        super(message, cause);
    }
}
