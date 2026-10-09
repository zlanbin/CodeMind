package com.codemind.review.domain.agent;

/**
 * 评审维度（ARCHITECTURE 5 并行维度，M5 引入）。
 *
 * <p>多 Agent 并行评审时，每个维度用独立 prompt 专注于该领域问题，
 * 最终按 {@code fingerprint} 去重合并。
 */
public enum ReviewDimension {

    /** 代码风格：命名、格式、注释、可读性 */
    STYLE,

    /** 安全：注入、认证、授权、敏感数据泄露 */
    SECURITY,

    /** 性能：算法复杂度、资源泄漏、不必要的计算 */
    PERFORMANCE,

    /** 测试：缺失的测试、测试质量、边界用例 */
    TEST
}
