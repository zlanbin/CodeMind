package com.codemind.review.domain.finding;

/**
 * 评审维度（ARCHITECTURE 5 DAG 图：Style / Security / Perf / Test 并行维度）。
 *
 * <p>{@code fingerprint} 用 category 参与计算，用于去重与增量判重（ARCHITECTURE 4）。
 */
public enum Category {
    STYLE,
    SECURITY,
    PERFORMANCE,
    TEST,
    CORRECTNESS,
    DESIGN
}
