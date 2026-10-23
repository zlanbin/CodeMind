package com.codemind.review.infrastructure.memory;

/** 调 memory 的 {@code POST /internal/memories/recall} 请求体（M9b，自行定义，不跨模块共享）。 */
public record RecallRequestDto(String project, String query, Integer topK, String kind, Double minScore) {
}
