package com.codemind.review.domain.chunk;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** 验证 jtokkit 估算的基本契约：非负、单调、空串为 0。 */
class JtokkitTokenEstimatorTest {

    private final TokenEstimator estimator = new JtokkitTokenEstimator();

    @Test
    void emptyAndNullAreZero() {
        assertThat(estimator.estimate("")).isZero();
        assertThat(estimator.estimate(null)).isZero();
    }

    @Test
    void nonEmptyTextHasPositiveTokens() {
        assertThat(estimator.estimate("public int add(int a, int b) { return a + b; }"))
                .isPositive();
    }

    @Test
    void longerTextEstimatesNoFewerTokens() {
        int shortCount = estimator.estimate("int a = 1;");
        int longCount = estimator.estimate("int a = 1; int b = 2; int c = 3; int d = 4;");
        assertThat(longCount).isGreaterThan(shortCount);
    }
}
