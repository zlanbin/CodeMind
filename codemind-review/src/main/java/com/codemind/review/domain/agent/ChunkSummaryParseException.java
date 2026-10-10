package com.codemind.review.domain.agent;

/** ChunkSummary 的 LLM 输出不合法。由 {@link ReviewAgent} 降级为空摘要（不影响同次产出的 Findings）。 */
public class ChunkSummaryParseException extends Exception {

    public ChunkSummaryParseException(String message) {
        super(message);
    }

    public ChunkSummaryParseException(String message, Throwable cause) {
        super(message, cause);
    }
}
