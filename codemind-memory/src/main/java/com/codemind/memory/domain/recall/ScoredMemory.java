package com.codemind.memory.domain.recall;

import com.codemind.memory.domain.memory.Memory;

/** 召回结果，带各因子便于调用方与测试核对公式。 */
public record ScoredMemory(Memory memory, double similarity, double decay, double score) {
}
