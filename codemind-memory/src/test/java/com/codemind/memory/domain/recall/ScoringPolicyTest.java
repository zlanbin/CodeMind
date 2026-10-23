package com.codemind.memory.domain.recall;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

class ScoringPolicyTest {

    private static final Instant NOW = Instant.parse("2026-10-23T00:00:00Z");
    private final ScoringPolicy policy = new ScoringPolicy(Duration.ofDays(30));

    @Test
    void decayHalvesEveryHalfLife() {
        assertThat(policy.decay(NOW, NOW)).isEqualTo(1.0);
        assertThat(policy.decay(NOW.minus(Duration.ofDays(30)), NOW)).isCloseTo(0.5, within(1e-12));
        assertThat(policy.decay(NOW.minus(Duration.ofDays(60)), NOW)).isCloseTo(0.25, within(1e-12));
        assertThat(policy.decay(NOW.minus(Duration.ofDays(15)), NOW)).isCloseTo(Math.sqrt(0.5), within(1e-12));
    }

    @Test
    void reinforcedInTheFutureDoesNotAmplify() {
        assertThat(policy.decay(NOW.plus(Duration.ofDays(3)), NOW)).isEqualTo(1.0);
    }

    @Test
    void scoreIsSimilarityTimesImportanceTimesDecay() {
        assertThat(policy.score(0.8, 0.5, 0.25)).isCloseTo(0.1, within(1e-12));
        assertThat(policy.score(0.0, 1.0, 1.0)).isZero();
    }

    @Test
    void retentionIgnoresSimilarity() {
        assertThat(policy.retention(0.4, NOW.minus(Duration.ofDays(30)), NOW)).isCloseTo(0.2, within(1e-12));
    }

    @Test
    void rejectsHalfLifeBelowOneMillisecond() {
        assertThatThrownBy(() -> new ScoringPolicy(Duration.ZERO)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ScoringPolicy(Duration.ofDays(-1))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ScoringPolicy(Duration.ofNanos(500_000))).isInstanceOf(IllegalArgumentException.class);
        assertThat(new ScoringPolicy(Duration.ofMillis(1)).decay(NOW.minusMillis(1), NOW)).isCloseTo(0.5, within(1e-12));
    }

    @Test
    void archiveCutoffNeverExcludesAMemoryThatRetentionWouldArchive() {
        double threshold = 0.05;
        Instant cutoff = policy.archiveCutoff(threshold, NOW).orElseThrow();

        // 截止时刻之前（含 importance=1 的最强情形）retention 一定 < 阈值的边界附近，逐毫秒检查
        for (long offset = -5; offset <= 5; offset++) {
            Instant reinforced = cutoff.plusMillis(offset);
            boolean wouldArchive = policy.retention(1.0, reinforced, NOW) < threshold;
            if (wouldArchive) {
                assertThat(reinforced).isBefore(cutoff);
            }
        }
        // 0.05 ≈ 2^-4.32：约 129.66 天
        assertThat(Duration.between(cutoff, NOW).toDays()).isEqualTo(129);
    }

    @Test
    void zeroThresholdArchivesNothing() {
        assertThat(policy.archiveCutoff(0, NOW)).isEmpty();
        assertThatThrownBy(() -> policy.archiveCutoff(1, NOW)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsFactorsOutsideUnitInterval() {
        assertThatThrownBy(() -> policy.score(1.2, 0.5, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> policy.score(0.5, Double.NaN, 1)).isInstanceOf(IllegalArgumentException.class);
    }
}
