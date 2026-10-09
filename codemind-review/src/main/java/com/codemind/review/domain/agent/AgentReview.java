package com.codemind.review.domain.agent;

import com.codemind.review.domain.finding.Finding;

import java.util.List;
import java.util.Objects;

/**
 * 单个 Agent 对一个 chunk 的评审结果。
 *
 * <p>{@code degraded=true} 表示重试耗尽后降级：不中断整个评审，返回空 Findings 并带降级原因，
 * 由下游（ChunkReduce / GlobalReduce）据此决策。{@code attempts} 为实际调用 LLM 的次数。
 *
 * @param findings      解析出的评审意见（降级时为空）
 * @param degraded      是否降级
 * @param degradeReason 降级原因（未降级时为空）
 * @param attempts      实际 LLM 调用次数
 */
public record AgentReview(
        List<Finding> findings,
        boolean degraded,
        String degradeReason,
        int attempts) {

    public AgentReview {
        findings = List.copyOf(Objects.requireNonNull(findings, "findings"));
    }

    public static AgentReview success(List<Finding> findings, int attempts) {
        return new AgentReview(findings, false, null, attempts);
    }

    public static AgentReview degraded(String reason, int attempts) {
        return new AgentReview(List.of(), true, reason, attempts);
    }
}
