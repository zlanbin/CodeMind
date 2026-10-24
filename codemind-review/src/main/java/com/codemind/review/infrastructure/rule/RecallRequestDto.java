package com.codemind.review.infrastructure.rule;

/** 调 rule 的 {@code POST /internal/rules/recall} 请求体（M11，自行定义，不跨模块共享）。 */
public record RecallRequestDto(String project, String query, Integer topK) {
}
