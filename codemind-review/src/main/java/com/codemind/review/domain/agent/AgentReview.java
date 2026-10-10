package com.codemind.review.domain.agent;

import com.codemind.review.domain.finding.Finding;

import java.util.List;
import java.util.Objects;

/**
 * 单个 Agent 对一个 chunk 的结果。
 *
 * <p>{@code degraded=true} 表示重试耗尽后降级：不中断整个评审，返回空 Findings 并带降级原因，
 * 由下游（ChunkReduce / GlobalReduce）据此决策。{@code attempts} 为实际调用 LLM 的次数。
 *
 * <p>M6 起多带一份 {@link ChunkSummary}（ARCHITECTURE 6.4）：同一次 LLM 调用里产出，
 * 供 GlobalReduce 跨 chunk 归约。摘要可单独降级——Findings 解析成功但摘要缺失时，
 * {@code summary.degraded()} 为 true 而本 record 的 {@code degraded} 仍为 false。
 *
 * @param findings      解析出的评审意见（降级时为空）
 * @param summary       结构化摘要（M6；摘要不可用时为降级摘要，不为 null）
 * @param degraded      Findings 是否降级
 * @param degradeReason 降级原因（未降级时为空）
 * @param attempts      实际 LLM 调用次数
 */
public record AgentReview(
        List<Finding> findings,
        ChunkSummary summary,
        boolean degraded,
        String degradeReason,
        int attempts) {

    public AgentReview {
        findings = List.copyOf(Objects.requireNonNull(findings, "findings"));
    }

    /** M4/M5 兼容签名：不带摘要（摘要为 null，表示该路径不产出摘要）。 */
    public static AgentReview success(List<Finding> findings, int attempts) {
        return new AgentReview(findings, null, false, null, attempts);
    }

    public static AgentReview success(List<Finding> findings, ChunkSummary summary, int attempts) {
        return new AgentReview(findings, summary, false, null, attempts);
    }

    public static AgentReview degraded(String reason, int attempts) {
        return new AgentReview(List.of(), null, true, reason, attempts);
    }

    public static AgentReview degraded(String reason, ChunkSummary summary, int attempts) {
        return new AgentReview(List.of(), summary, true, reason, attempts);
    }

    public boolean hasSummary() {
        return summary != null;
    }
}
