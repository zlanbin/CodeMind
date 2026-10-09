package com.codemind.review.domain.dag;

import com.codemind.review.domain.finding.Finding;

import java.util.List;

/**
 * Chunk 归约结果（M5）：去重后的 Finding + 降级信息。
 *
 * @param chunkId       切片 ID
 * @param findings      去重后的 Finding 列表
 * @param degradations  降级原因列表（空列表表示所有 Agent 都成功）
 */
public record ChunkReduceResult(
        String chunkId,
        List<Finding> findings,
        List<String> degradations) {

    public ChunkReduceResult {
        findings = List.copyOf(findings);
        degradations = List.copyOf(degradations);
    }

    public boolean hasDegradation() {
        return !degradations.isEmpty();
    }
}
