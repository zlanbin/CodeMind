package com.codemind.rule.domain.rule;

import java.util.List;

/** 规范文档切块仓储端口（M11）。 */
public interface RuleChunkRepository {

    /** 批量插入某文档的全部切块（embedding 已算好）。 */
    void insertAll(long documentId, String project, List<RuleChunk> chunks, List<float[]> embeddings);

    /**
     * 只取本 project 的 ACTIVE 候选：向量最近的 limit 条 ∪ 三元组相似度最高的 limit 条，
     * 两路都精确扫描（不建近似索引，同 memory M8 结论）。
     */
    List<RuleChunkCandidate> findCandidates(String project, String queryText, float[] queryEmbedding, int limit);
}
