package com.codemind.review.domain.chunk;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** ChunkBudget 单测：diff 预算按 30% 计算，非法参数拦截。 */
class ChunkBudgetTest {

    @Test
    void diffBudgetIsThirtyPercentOfTotal() {
        ChunkBudget budget = ChunkBudget.ofDefault();
        assertThat(budget.totalBudget()).isEqualTo(128_000);
        assertThat(budget.diffBudget()).isEqualTo((int) (128_000 * 0.30));
    }

    @Test
    void ofDiffBudgetUsesWholeBudgetForDiff() {
        ChunkBudget budget = ChunkBudget.ofDiffBudget(500);
        assertThat(budget.diffBudget()).isEqualTo(500);
    }

    @Test
    void rejectsNonPositiveAndOutOfRangeShare() {
        assertThatThrownBy(() -> new ChunkBudget(0, 0.3)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ChunkBudget(100, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ChunkBudget(100, 1.5)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ChunkBudget.ofDiffBudget(-1)).isInstanceOf(IllegalArgumentException.class);
    }
}
