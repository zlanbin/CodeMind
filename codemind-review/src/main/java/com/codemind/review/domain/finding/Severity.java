package com.codemind.review.domain.finding;

/**
 * 评审意见严重级别。LLM 输出需落在此集合内，否则 {@link FindingJsonParser} 判为非法。
 */
public enum Severity {
    BLOCKER,
    CRITICAL,
    MAJOR,
    MINOR,
    INFO
}
