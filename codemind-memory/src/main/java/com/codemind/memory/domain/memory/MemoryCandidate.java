package com.codemind.memory.domain.memory;

import java.util.Objects;

/**
 * 仓储召回的候选：两路相似度原样带回，融合与打分在 {@code MemoryRanker}。
 *
 * @param vectorSimilarity 向量余弦相似度，可能为负或因浮点略超 1
 * @param textSimilarity   pg_trgm 三元组相似度
 */
public record MemoryCandidate(Memory memory, double vectorSimilarity, double textSimilarity) {

    public MemoryCandidate {
        Objects.requireNonNull(memory, "memory");
    }
}
