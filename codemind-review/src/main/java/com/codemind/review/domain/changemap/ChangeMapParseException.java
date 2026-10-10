package com.codemind.review.domain.changemap;

/** ChangeMap 的 LLM 输出不合法（非 JSON、缺必填字段）。由 {@link ChangeMapAgent} 重试 / 降级。 */
public class ChangeMapParseException extends Exception {

    public ChangeMapParseException(String message) {
        super(message);
    }

    public ChangeMapParseException(String message, Throwable cause) {
        super(message, cause);
    }
}
