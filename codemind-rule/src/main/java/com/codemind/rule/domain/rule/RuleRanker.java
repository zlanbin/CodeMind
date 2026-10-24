package com.codemind.rule.domain.rule;

import java.util.Collection;
import java.util.Comparator;
import java.util.List;

/**
 * 规则召回排序（M11 D4，简化于 memory M8 的 MemoryRanker）：{@code score = clamp(similarity)}，
 * 不含 importance/decay——规则条款没有"重要性/衰减"概念，只有 ACTIVE/ARCHIVED 两态（版本替换，
 * 不是"强化"）。NaN 相似度按 0 处理；并列按 chunk id 升序保证结果确定。
 */
public final class RuleRanker {

    private static final Comparator<ScoredRuleChunk> ORDER = Comparator
            .comparingDouble(ScoredRuleChunk::score).reversed()
            .thenComparingLong(s -> s.chunk().id());

    public List<ScoredRuleChunk> rank(Collection<RuleChunkCandidate> candidates, int topK) {
        if (topK < 1) {
            throw new IllegalArgumentException("topK 必须 >= 1：" + topK);
        }
        return candidates.stream()
                .map(c -> new ScoredRuleChunk(c.chunk(), c.documentTitle(), clamp(c.similarity())))
                .sorted(ORDER)
                .limit(topK)
                .toList();
    }

    /** NaN（如零向量的余弦）按 0 处理。 */
    private static double clamp(double value) {
        if (Double.isNaN(value)) {
            return 0;
        }
        return Math.min(1, Math.max(0, value));
    }
}
