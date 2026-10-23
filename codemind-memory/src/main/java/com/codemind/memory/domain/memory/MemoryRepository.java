package com.codemind.memory.domain.memory;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** L2 记忆仓储端口。 */
public interface MemoryRepository {

    /**
     * 按 {@code (project, kind, content.hash)} 写入或强化：已存在时 importance 取较大者、
     * {@code reinforcedAt} 刷新为 now、ARCHIVED 复活为 ACTIVE。
     */
    UpsertResult upsert(String project, MemoryKind kind, MemoryContent content, double importance,
                        float[] embedding, Instant now);

    /**
     * 只取本 project 的 ACTIVE 候选：向量最近的 limit 条 ∪ 三元组相似度最高的 limit 条，两路相似度都带回。
     * 每一路都必须是精确 top-limit（不得因近似索引先取全局近邻再过滤而少返回）。
     *
     * @param kind 为 null 表示不限类型
     */
    List<MemoryCandidate> findCandidates(String project, MemoryKind kind, String queryText,
                                         float[] queryEmbedding, int limit);

    /** 归档预筛：ACTIVE 且 {@code reinforcedAt < cutoff}。 */
    List<Memory> findActiveReinforcedBefore(String project, Instant cutoff);

    /** 仅当记忆仍是 ACTIVE 且 reinforcedAt 未变时归档，避免覆盖并发的强化。 */
    boolean archiveIfUnchanged(long id, Instant expectedReinforcedAt, Instant now);

    boolean delete(long id);

    Optional<Memory> findById(long id);

    record UpsertResult(Memory memory, boolean created) {
    }
}
