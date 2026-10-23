package com.codemind.memory.domain.recall;

import com.codemind.memory.domain.memory.MemoryCandidate;
import com.codemind.memory.domain.memory.MemoryStatus;

import java.time.Instant;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 混合召回的排序：丢弃非 ACTIVE → 同一条记忆的两路候选合并 →
 * {@code similarity = max(clamp(向量), clamp(三元组))} →
 * 按 {@link ScoringPolicy} 打分 → 过滤 {@code score >= minScore} → 排序取 top-k。
 *
 * <p>并列时 importance 高者优先，再按 id 升序，保证结果确定。
 */
public final class MemoryRanker {

    private static final Comparator<ScoredMemory> ORDER = Comparator
            .comparingDouble(ScoredMemory::score).reversed()
            .thenComparing(Comparator.comparingDouble((ScoredMemory s) -> s.memory().importance()).reversed())
            .thenComparingLong(s -> s.memory().id());

    private final ScoringPolicy policy;

    public MemoryRanker(ScoringPolicy policy) {
        this.policy = Objects.requireNonNull(policy, "policy");
    }

    public List<ScoredMemory> rank(Collection<MemoryCandidate> candidates, Instant now, int topK, double minScore) {
        Objects.requireNonNull(now, "now");
        if (topK < 1) {
            throw new IllegalArgumentException("topK 必须 >= 1：" + topK);
        }
        if (!(minScore >= 0 && minScore <= 1)) {
            throw new IllegalArgumentException("minScore 必须在 [0, 1] 内：" + minScore);
        }
        Map<Long, MemoryCandidate> merged = new LinkedHashMap<>();
        for (MemoryCandidate raw : candidates) {
            if (raw.memory().status() != MemoryStatus.ACTIVE) {
                continue;
            }
            // 先逐路 clamp 再合并：NaN 不能经 Math.max 传染到另一路
            MemoryCandidate c = new MemoryCandidate(raw.memory(),
                    clamp(raw.vectorSimilarity()), clamp(raw.textSimilarity()));
            merged.merge(c.memory().id(), c, (a, b) -> new MemoryCandidate(a.memory(),
                    Math.max(a.vectorSimilarity(), b.vectorSimilarity()),
                    Math.max(a.textSimilarity(), b.textSimilarity())));
        }
        return merged.values().stream()
                .map(c -> score(c, now))
                .filter(s -> s.score() >= minScore)
                .sorted(ORDER)
                .limit(topK)
                .toList();
    }

    private ScoredMemory score(MemoryCandidate c, Instant now) {
        double similarity = Math.max(c.vectorSimilarity(), c.textSimilarity());
        double decay = policy.decay(c.memory().reinforcedAt(), now);
        return new ScoredMemory(c.memory(), similarity, decay,
                policy.score(similarity, c.memory().importance(), decay));
    }

    /** NaN（如零向量的余弦）按 0 处理。 */
    private static double clamp(double value) {
        if (Double.isNaN(value)) {
            return 0;
        }
        return Math.min(1, Math.max(0, value));
    }
}
