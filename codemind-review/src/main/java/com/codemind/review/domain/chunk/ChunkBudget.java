package com.codemind.review.domain.chunk;

/**
 * 单个子会话的 token 预算（ARCHITECTURE 6.3）。
 *
 * <p>子会话总预算按比例分给：系统提示 10% / ChangeMap 5% / 规则+记忆 15% /
 * 召回上下文 25% / <b>diff 本体 30%</b> / 输出预留 15%。切片阶段只约束 diff 本体部分，
 * 故 {@link #diffBudget()} = 总预算 × 30%。
 *
 * <p>默认总预算 128k（主流模型上下文窗口）。单测可传入小值构造超预算退化场景。
 */
public record ChunkBudget(int totalBudget, double diffShare) {

    /** diff 本体占总预算的比例（ARCHITECTURE 6.3）。 */
    public static final double DIFF_SHARE = 0.30;

    /** 默认 128k 上下文窗口。 */
    public static final int DEFAULT_TOTAL_BUDGET = 128_000;

    public ChunkBudget {
        if (totalBudget <= 0) {
            throw new IllegalArgumentException("totalBudget 必须为正数，实际 " + totalBudget);
        }
        if (diffShare <= 0 || diffShare > 1) {
            throw new IllegalArgumentException("diffShare 必须在 (0,1]，实际 " + diffShare);
        }
    }

    public static ChunkBudget ofDefault() {
        return new ChunkBudget(DEFAULT_TOTAL_BUDGET, DIFF_SHARE);
    }

    /** 直接按 diff token 上限构造（单测构造退化场景时最直观）。 */
    public static ChunkBudget ofDiffBudget(int diffBudget) {
        if (diffBudget <= 0) {
            throw new IllegalArgumentException("diffBudget 必须为正数，实际 " + diffBudget);
        }
        return new ChunkBudget(diffBudget, 1.0);
    }

    /** 分给 diff 本体的 token 上限。 */
    public int diffBudget() {
        return (int) Math.floor(totalBudget * diffShare);
    }
}
