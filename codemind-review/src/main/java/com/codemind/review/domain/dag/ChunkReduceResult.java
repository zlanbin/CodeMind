package com.codemind.review.domain.dag;

import com.codemind.review.domain.agent.ChunkSummary;
import com.codemind.review.domain.finding.Finding;

import java.util.List;

/**
 * Chunk 归约结果（M5）：去重后的 Finding + 降级信息；M6 起多带一份合并后的 {@link ChunkSummary}。
 *
 * @param chunkId      切片 ID
 * @param findings     去重后的 Finding 列表
 * @param summary      多维度摘要合并后的本片摘要（M6；无摘要时为 null）
 * @param degradations 降级原因列表（空列表表示所有 Agent 都成功，且摘要完整）
 */
public record ChunkReduceResult(
        String chunkId,
        List<Finding> findings,
        ChunkSummary summary,
        List<String> degradations) {

    public ChunkReduceResult {
        findings = List.copyOf(findings);
        degradations = List.copyOf(degradations);
    }

    /** M5 兼容签名：无摘要。 */
    public ChunkReduceResult(String chunkId, List<Finding> findings, List<String> degradations) {
        this(chunkId, findings, null, degradations);
    }

    public boolean hasDegradation() {
        return !degradations.isEmpty();
    }

    public boolean hasSummary() {
        return summary != null;
    }
}
