package com.codemind.review.infrastructure.memory;

import java.util.List;

/** memory 的 {@code POST /internal/memories/recall} 响应体（M9b，自行定义，不跨模块共享）。 */
public record RecallResponseDto(List<Item> memories) {

    public record Item(Memory memory, double similarity, double decay, double score) {
    }

    public record Memory(long id, String project, String kind, String content, double importance, String status) {
    }
}
