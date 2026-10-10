package com.codemind.review.domain.agent;

import com.codemind.review.domain.finding.Finding;

import java.util.List;
import java.util.Objects;

/**
 * 全局归约结果（M6）：跨文件 Finding + 降级信息。
 *
 * <p>与 {@link AgentReview} 同构：LLM 失败不抛异常，降级为空 Findings + 原因，
 * 让 chunk 级的意见照常发布。
 *
 * @param findings      跨文件评审意见（降级时为空）
 * @param degraded      是否降级
 * @param degradeReason 降级原因（未降级为 null）
 * @param attempts      实际 LLM 调用次数
 */
public record GlobalReduceResult(
        List<Finding> findings,
        boolean degraded,
        String degradeReason,
        int attempts) {

    public GlobalReduceResult {
        findings = List.copyOf(Objects.requireNonNull(findings, "findings"));
    }

    public static GlobalReduceResult success(List<Finding> findings, int attempts) {
        return new GlobalReduceResult(findings, false, null, attempts);
    }

    public static GlobalReduceResult degraded(String reason, int attempts) {
        return new GlobalReduceResult(List.of(), true, reason, attempts);
    }
}
