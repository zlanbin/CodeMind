package com.codemind.memory.domain.recall;

import com.codemind.memory.domain.memory.MemoryCandidate;
import com.codemind.memory.domain.memory.Memory;
import com.codemind.memory.domain.memory.MemoryKind;
import com.codemind.memory.domain.memory.MemoryStatus;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

/** M8 验证点：召回排序符合 {@code score = similarity × importance × decay(Δt)}。 */
class MemoryRankerTest {

    private static final Instant NOW = Instant.parse("2026-10-23T00:00:00Z");
    private static final Duration HALF_LIFE = Duration.ofDays(30);
    private final MemoryRanker ranker = new MemoryRanker(new ScoringPolicy(HALF_LIFE));

    @Test
    void importanceCanOutweighHigherSimilarity() {
        var lowImportance = candidate(1, 0.2, NOW, 0.9, 0.0);   // 0.9 × 0.2 = 0.18
        var highImportance = candidate(2, 0.9, NOW, 0.6, 0.0);  // 0.6 × 0.9 = 0.54

        List<ScoredMemory> ranked = ranker.rank(List.of(lowImportance, highImportance), NOW, 5, 0);

        assertThat(ranked).extracting(s -> s.memory().id()).containsExactly(2L, 1L);
        assertThat(ranked.get(0).score()).isCloseTo(0.54, within(1e-12));
        assertThat(ranked.get(1).score()).isCloseTo(0.18, within(1e-12));
    }

    @Test
    void decayDemotesStaleMemory() {
        var stale = candidate(1, 0.8, NOW.minus(Duration.ofDays(90)), 0.8, 0.0); // 0.8 × 0.8 × 0.125 = 0.08
        var fresh = candidate(2, 0.5, NOW, 0.5, 0.0);                             // 0.25

        List<ScoredMemory> ranked = ranker.rank(List.of(stale, fresh), NOW, 5, 0);

        assertThat(ranked).extracting(s -> s.memory().id()).containsExactly(2L, 1L);
        assertThat(ranked.get(1).decay()).isCloseTo(0.125, within(1e-12));
        assertThat(ranked.get(1).score()).isCloseTo(0.08, within(1e-12));
    }

    @Test
    void similarityIsTheStrongerChannelClampedToUnitInterval() {
        var textWins = candidate(1, 1.0, NOW, 0.1, 0.7);
        var vectorWins = candidate(2, 1.0, NOW, 0.6, 0.2);
        var negativeVector = candidate(3, 1.0, NOW, -0.4, 0.0);
        var roundingOver = candidate(4, 1.0, NOW, 1.0000001, 0.0);

        List<ScoredMemory> ranked = ranker.rank(List.of(textWins, vectorWins, negativeVector, roundingOver), NOW, 5, 0);

        assertThat(ranked).extracting(ScoredMemory::similarity).containsExactly(1.0, 0.7, 0.6, 0.0);
    }

    @Test
    void everyScoreMatchesFormulaAndOrderIsByScore() {
        Random random = new Random(20261023L);
        List<MemoryCandidate> candidates = new ArrayList<>();
        for (int i = 1; i <= 40; i++) {
            double importance = 0.05 + random.nextDouble() * 0.95;
            Instant reinforced = NOW.minus(Duration.ofHours(random.nextInt(24 * 120)));
            candidates.add(candidate(i, importance, reinforced, random.nextDouble(), random.nextDouble()));
        }
        int topK = 10;

        List<ScoredMemory> ranked = ranker.rank(candidates, NOW, topK, 0);

        // 独立按公式重算一遍，不复用被测类的任何方法
        List<Long> expected = candidates.stream()
                .sorted(Comparator.comparingDouble(MemoryRankerTest::expectedScore).reversed())
                .limit(topK)
                .map(c -> c.memory().id())
                .toList();
        assertThat(ranked).extracting(s -> s.memory().id()).containsExactlyElementsOf(expected);
        for (ScoredMemory s : ranked) {
            MemoryCandidate source = candidates.get((int) s.memory().id() - 1);
            assertThat(s.score()).isCloseTo(expectedScore(source), within(1e-12));
            assertThat(s.score()).isCloseTo(s.similarity() * s.memory().importance() * s.decay(), within(1e-12));
        }
    }

    @Test
    void sameMemoryFromBothChannelsIsMergedKeepingEachChannelMax() {
        var fromVector = candidate(7, 0.5, NOW, 0.4, 0.0);
        var fromText = candidate(7, 0.5, NOW, 0.0, 0.6);

        List<ScoredMemory> ranked = ranker.rank(List.of(fromVector, fromText), NOW, 5, 0);

        assertThat(ranked).hasSize(1);
        assertThat(ranked.get(0).similarity()).isEqualTo(0.6);
    }

    @Test
    void tiesBreakByImportanceThenId() {
        var a = candidate(3, 0.5, NOW, 0.8, 0.0);  // 0.40
        var b = candidate(1, 0.8, NOW, 0.5, 0.0);  // 0.40，importance 更高
        var c = candidate(2, 0.5, NOW, 0.8, 0.0);  // 0.40，与 a 全同，id 更小

        List<ScoredMemory> ranked = ranker.rank(List.of(a, b, c), NOW, 5, 0);

        assertThat(ranked).extracting(s -> s.memory().id()).containsExactly(1L, 2L, 3L);
    }

    @Test
    void appliesTopKAndMinScore() {
        var high = candidate(1, 1.0, NOW, 0.9, 0.0);
        var mid = candidate(2, 1.0, NOW, 0.5, 0.0);
        var low = candidate(3, 1.0, NOW, 0.1, 0.0);

        assertThat(ranker.rank(List.of(low, mid, high), NOW, 2, 0))
                .extracting(s -> s.memory().id()).containsExactly(1L, 2L);
        assertThat(ranker.rank(List.of(low, mid, high), NOW, 5, 0.5))
                .extracting(s -> s.memory().id()).containsExactly(1L, 2L);
        assertThat(ranker.rank(List.of(), NOW, 5, 0)).isEmpty();
    }

    @Test
    void nanInOneChannelDoesNotSwallowTheOther() {
        // 零向量的 pgvector 余弦是 NaN；Math.max(NaN, 0.9) 是 NaN，必须逐路 clamp
        var textStrong = candidate(1, 1.0, NOW, Double.NaN, 0.9);
        var vectorStrong = candidate(2, 1.0, NOW, 0.7, Double.NaN);

        List<ScoredMemory> ranked = ranker.rank(List.of(textStrong, vectorStrong), NOW, 5, 0);

        assertThat(ranked).extracting(ScoredMemory::similarity).containsExactly(0.9, 0.7);
    }

    @Test
    void archivedCandidatesAreNeverRanked() {
        Memory archived = new Memory(9, "codemind/demo", MemoryKind.CONVENTION, "m", 1.0, MemoryStatus.ARCHIVED,
                NOW, NOW);

        assertThat(ranker.rank(List.of(new MemoryCandidate(archived, 1.0, 1.0)), NOW, 5, 0)).isEmpty();
    }

    @Test
    void rejectsInvalidArguments() {
        assertThatThrownBy(() -> ranker.rank(List.of(), NOW, 0, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ranker.rank(List.of(), NOW, 5, 1.5)).isInstanceOf(IllegalArgumentException.class);
    }

    private static double expectedScore(MemoryCandidate c) {
        double similarity = Math.min(1.0, Math.max(0.0, Math.max(c.vectorSimilarity(), c.textSimilarity())));
        double days = Duration.between(c.memory().reinforcedAt(), NOW).toMillis() / 86_400_000.0;
        return similarity * c.memory().importance() * Math.pow(0.5, days / 30.0);
    }

    static MemoryCandidate candidate(long id, double importance, Instant reinforcedAt,
                                     double vectorSimilarity, double textSimilarity) {
        Memory memory = new Memory(id, "codemind/demo", MemoryKind.CONVENTION, "memory-" + id, importance,
                MemoryStatus.ACTIVE, reinforcedAt, reinforcedAt);
        return new MemoryCandidate(memory, vectorSimilarity, textSimilarity);
    }
}
