package com.codemind.rule.domain.rule;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

/** M11：规则召回打分——score = clamp(similarity)，无 importance/decay（简化于 M8 MemoryRanker）。 */
class RuleRankerTest {

    private final RuleRanker ranker = new RuleRanker();

    @Test
    void ordersByScoreDescending() {
        var low = candidate(1, 0.2);
        var high = candidate(2, 0.9);

        List<ScoredRuleChunk> ranked = ranker.rank(List.of(low, high), 5);

        assertThat(ranked).extracting(s -> s.chunk().id()).containsExactly(2L, 1L);
        assertThat(ranked.get(0).score()).isCloseTo(0.9, within(1e-12));
        assertThat(ranked.get(1).score()).isCloseTo(0.2, within(1e-12));
    }

    @Test
    void nanSimilarityTreatedAsZero() {
        var nan = candidate(1, Double.NaN);
        var normal = candidate(2, 0.5);

        List<ScoredRuleChunk> ranked = ranker.rank(List.of(nan, normal), 5);

        assertThat(ranked).extracting(s -> s.score()).containsExactly(0.5, 0.0);
    }

    @Test
    void tiesBreakById() {
        var a = candidate(3, 0.5);
        var b = candidate(1, 0.5);
        var c = candidate(2, 0.5);

        List<ScoredRuleChunk> ranked = ranker.rank(List.of(a, b, c), 5);

        assertThat(ranked).extracting(s -> s.chunk().id()).containsExactly(1L, 2L, 3L);
    }

    @Test
    void appliesTopK() {
        var low = candidate(1, 0.1);
        var mid = candidate(2, 0.5);
        var high = candidate(3, 0.9);

        List<ScoredRuleChunk> ranked = ranker.rank(List.of(low, mid, high), 2);

        assertThat(ranked).extracting(s -> s.chunk().id()).containsExactly(3L, 2L);
    }

    @Test
    void rejectsNonPositiveTopK() {
        assertThatThrownBy(() -> ranker.rank(List.of(), 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ranker.rank(List.of(), -1)).isInstanceOf(IllegalArgumentException.class);
    }

    private static RuleChunkCandidate candidate(long id, double similarity) {
        RuleChunk chunk = new RuleChunk(id, 1L, "proj", "路径" + id, "标题" + id, "正文" + id);
        return new RuleChunkCandidate(chunk, "文档标题", similarity);
    }
}
