package com.codemind.memory.domain.recall;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * 记忆打分公式（ARCHITECTURE 7.3）：{@code score = similarity × importance × decay(Δt)}，
 * {@code decay(Δt) = 0.5^(Δt / halfLife)}。
 *
 * <p>遗忘用 {@code retention = importance × decay(Δt)}：「长期低于阈值」与具体查询无关，不含 similarity。
 */
public final class ScoringPolicy {

    private final Duration halfLife;

    public ScoringPolicy(Duration halfLife) {
        Objects.requireNonNull(halfLife, "halfLife");
        // decay 按毫秒算，不足 1ms 会除以 0
        if (halfLife.toMillis() < 1) {
            throw new IllegalArgumentException("半衰期必须至少 1ms：" + halfLife);
        }
        this.halfLife = halfLife;
    }

    /** Δt 按毫秒计；reinforcedAt 晚于 now（时钟漂移）按 Δt=0，不放大分数。 */
    public double decay(Instant reinforcedAt, Instant now) {
        long elapsed = Math.max(0, Duration.between(reinforcedAt, now).toMillis());
        return Math.pow(0.5, (double) elapsed / halfLife.toMillis());
    }

    public double score(double similarity, double importance, double decay) {
        return unit(similarity, "similarity") * unit(importance, "importance") * unit(decay, "decay");
    }

    public double retention(double importance, Instant reinforcedAt, Instant now) {
        return unit(importance, "importance") * decay(reinforcedAt, now);
    }

    /**
     * 归档的数据库侧预筛：importance ≤ 1，所以 {@code retention < threshold} 的记忆
     * 必然 reinforcedAt 早于 {@code now - halfLife × log2(1/threshold)}。
     * 返回的时刻向下取整到毫秒，只会多放进几条，精确判定仍用 {@link #retention}。
     *
     * @return threshold 为 0 时没有记忆会被归档，返回 empty
     */
    public Optional<Instant> archiveCutoff(double threshold, Instant now) {
        if (!(threshold >= 0 && threshold < 1)) {
            throw new IllegalArgumentException("threshold 必须在 [0, 1) 内：" + threshold);
        }
        if (threshold == 0) {
            return Optional.empty();
        }
        double halfLives = Math.log(1 / threshold) / Math.log(2);
        long ageMillis = (long) Math.floor(halfLives * halfLife.toMillis());
        return Optional.of(now.minusMillis(ageMillis).plusMillis(1));
    }

    public Duration halfLife() {
        return halfLife;
    }

    private static double unit(double value, String name) {
        if (!(value >= 0 && value <= 1)) {
            throw new IllegalArgumentException(name + " 必须在 [0, 1] 内：" + value);
        }
        return value;
    }
}
