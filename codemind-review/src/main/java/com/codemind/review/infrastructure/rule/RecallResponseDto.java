package com.codemind.review.infrastructure.rule;

import java.util.List;

/** rule 的 {@code POST /internal/rules/recall} 响应体（M11，自行定义，不跨模块共享）。 */
public record RecallResponseDto(List<Chunk> chunks) {

    public record Chunk(String documentTitle, String sectionPath, String heading, String content,
                        double similarity) {
    }
}
