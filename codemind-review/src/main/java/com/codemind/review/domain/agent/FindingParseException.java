package com.codemind.review.domain.agent;

/**
 * LLM 输出无法解析为合法 Finding JSON（非 JSON、字段缺失、枚举非法等）。
 *
 * <p>触发 {@link ReviewAgent} 的重试；重试耗尽后降级。
 */
public class FindingParseException extends Exception {

    public FindingParseException(String message) {
        super(message);
    }

    public FindingParseException(String message, Throwable cause) {
        super(message, cause);
    }
}
