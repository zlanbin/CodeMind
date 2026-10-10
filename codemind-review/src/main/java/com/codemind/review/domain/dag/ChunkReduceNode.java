package com.codemind.review.domain.dag;

import com.codemind.review.domain.agent.AgentReview;
import com.codemind.review.domain.agent.ChunkSummary;
import com.codemind.review.domain.finding.Finding;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * Chunk 归约节点（ARCHITECTURE 5，M5 引入）：按 fingerprint 去重多 Agent 并行结果。
 *
 * <p>单个 chunk 的多个维度 Agent 并行评审后，相同 {@code fingerprint} 的 Finding 只保留
 * 一条（按 severity 降序优先；同 severity 则保留第一个遇到的）。
 *
 * <p>M6 起还要把多个维度各自产出的 {@link ChunkSummary} 合并成本片的一份摘要，并<b>校验必填字段</b>
 * （{@code changedSymbols} / {@code intent} 非空）。摘要不完整时记入 {@code degradations}——
 * 否则 GlobalReduce 会因为摘要为空而静默失效（M6 设计审查的最高风险项）。
 *
 * <p>输入：若干个 {@code ReviewChunkNode} 的 {@link AgentReview} 结果（从 {@link NodeContext} 读取）；
 * 输出：{@link ChunkReduceResult}（去重后的 Finding 列表 + 合并摘要 + 降级信息）。
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

        // 合并多维度摘要并校验必填字段
        ChunkSummary merged = mergeSummaries(reviews);
        if (merged != null && !merged.isComplete()) {
            degradations.add("chunk %s 摘要不完整（changedSymbols=%d, intent=%s）".formatted(
                    chunkId, merged.changedSymbols().size(), merged.intent().isBlank() ? "空" : "有"));
        }

        log.info("chunk {} 归约完成：{} 条原始意见 → {} 条去重后，摘要{}（{} 项降级）",
                chunkId, allFindings.size(), deduped.size(),
                merged == null ? "无" : (merged.isComplete() ? "完整" : "不完整"), degradations.size());

        return new ChunkReduceResult(chunkId, deduped, merged, degradations);
    }

    /**
     * 合并多个维度 Agent 的摘要：{@code changedSymbols} / {@code risks} /
     * {@code crossChunkQuestions} 取并集（保持顺序、去重），{@code intent} 取第一个非空的。
     *
     * <p>各维度看的是同一份 diff，变更符号本应一致；取并集是为了容忍某个维度漏写。
     * 全部维度都没有摘要时返回 null（M5 路径）；有摘要但全部降级时返回降级摘要。
     */
    private ChunkSummary mergeSummaries(List<AgentReview> reviews) {
        List<ChunkSummary> summaries = reviews.stream()
                .map(AgentReview::summary)
                .filter(Objects::nonNull)
                .toList();
        if (summaries.isEmpty()) {
            return null;
        }

        Set<String> symbols = new LinkedHashSet<>();
        Set<String> risks = new LinkedHashSet<>();
        Set<String> questions = new LinkedHashSet<>();
        String intent = "";
        List<String> reasons = new ArrayList<>();

        for (ChunkSummary s : summaries) {
            symbols.addAll(s.changedSymbols());
            risks.addAll(s.risks());
            questions.addAll(s.crossChunkQuestions());
            if (intent.isBlank() && !s.intent().isBlank()) {
                intent = s.intent();
            }
            if (s.degraded() && s.degradeReason() != null) {
                reasons.add(s.degradeReason());
            }
        }

        if (symbols.isEmpty() && intent.isBlank()) {
            return ChunkSummary.degraded(chunkId,
                    reasons.isEmpty() ? "所有维度均未产出可用摘要" : String.join("; ", reasons));
        }
        return ChunkSummary.of(chunkId, List.copyOf(symbols), intent,
                List.copyOf(risks), List.copyOf(questions));
    }

    public String chunkId() {
        return chunkId;
    }
}
