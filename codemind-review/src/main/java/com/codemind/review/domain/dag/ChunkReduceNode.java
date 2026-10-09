package com.codemind.review.domain.dag;

import com.codemind.review.domain.agent.AgentReview;
import com.codemind.review.domain.finding.Finding;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Chunk 归约节点（ARCHITECTURE 5，M5 引入）：按 fingerprint 去重多 Agent 并行结果。
 *
 * <p>单个 chunk 的多个维度 Agent 并行评审后，相同 {@code fingerprint} 的 Finding 只保留
 * 一条（按 severity 降序优先；同 severity 则保留第一个遇到的）。
 *
 * <p>输入：若干个 {@code ReviewChunkNode} 的 {@link AgentReview} 结果（从 {@link NodeContext} 读取）；
 * 输出：{@link ChunkReduceResult}（去重后的 Finding 列表 + 降级信息）。
 */
public final class ChunkReduceNode implements Node {

    private static final Logger log = LoggerFactory.getLogger(ChunkReduceNode.class);

    private final String nodeId;
    private final String chunkId;
    private final List<String> sourceNodeIds;  // 上游多个 ReviewChunkNode 的 id

    public ChunkReduceNode(String nodeId, String chunkId, List<String> sourceNodeIds) {
        this.nodeId = Objects.requireNonNull(nodeId, "nodeId");
        this.chunkId = Objects.requireNonNull(chunkId, "chunkId");
        this.sourceNodeIds = List.copyOf(sourceNodeIds);
        if (sourceNodeIds.isEmpty()) {
            throw new IllegalArgumentException("sourceNodeIds 不能为空");
        }
    }

    @Override
    public String id() {
        return nodeId;
    }

    @Override
    public ChunkReduceResult execute(NodeContext context) {
        List<AgentReview> reviews = new ArrayList<>();
        for (String sourceId : sourceNodeIds) {
            Object result = context.completedResults().get(sourceId);
            if (result == null) {
                throw new IllegalStateException("上游节点 " + sourceId + " 未完成");
            }
            if (!(result instanceof AgentReview)) {
                throw new IllegalStateException("上游节点 " + sourceId + " 结果类型非法: " + result.getClass());
            }
            reviews.add((AgentReview) result);
        }

        // 收集所有 Finding 和降级信息
        List<Finding> allFindings = new ArrayList<>();
        List<String> degradations = new ArrayList<>();
        for (AgentReview review : reviews) {
            allFindings.addAll(review.findings());
            if (review.degraded()) {
                degradations.add(review.degradeReason());
            }
        }

        // 按 fingerprint 去重：同 fingerprint 只保留 severity 最高的一条
        Map<String, Finding> dedupMap = new LinkedHashMap<>();
        for (Finding f : allFindings) {
            String fp = f.fingerprint();
            Finding existing = dedupMap.get(fp);
            if (existing == null || f.severity().compareTo(existing.severity()) < 0) {
                // severity 枚举按 ordinal 升序，compareTo < 0 表示 f 更严重（BLOCKER < INFO）
                dedupMap.put(fp, f);
            }
        }

        List<Finding> deduped = new ArrayList<>(dedupMap.values());
        log.info("chunk {} 归约完成：{} 条原始意见 → {} 条去重后（{} 个 Agent 有降级）",
                chunkId, allFindings.size(), deduped.size(), degradations.size());

        return new ChunkReduceResult(chunkId, deduped, degradations);
    }

    public String chunkId() {
        return chunkId;
    }
}
