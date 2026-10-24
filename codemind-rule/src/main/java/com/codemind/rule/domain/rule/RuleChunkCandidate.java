package com.codemind.rule.domain.rule;

import java.util.Objects;

/**
 * 仓储召回的候选：两路相似度已在 SQL 层合并为单一值（不像 memory 的 MemoryCandidate 拆两路，
 * 规则条款没有 importance/decay 需要在 domain 层二次融合）。
 */
public record RuleChunkCandidate(RuleChunk chunk, String documentTitle, double similarity) {

    public RuleChunkCandidate {
        Objects.requireNonNull(chunk, "chunk");
        Objects.requireNonNull(documentTitle, "documentTitle");
    }
}
