package com.codemind.rule.infrastructure.embedding;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class HashingEmbedderTest {

    private final HashingEmbedder embedder = new HashingEmbedder();

    @Test
    void isDeterministicAndUnitLength() {
        float[] a = embedder.embed("公开方法命名必须用名词加动词结构");
        float[] b = embedder.embed("公开方法命名必须用名词加动词结构");

        assertThat(a).hasSize(HashingEmbedder.DIMENSIONS).containsExactly(b);
        assertThat(norm(a)).isCloseTo(1.0, within(1e-6));
    }

    @Test
    void relatedTextIsCloserThanUnrelatedText() {
        float[] rule = embedder.embed("公开方法命名必须用名词加动词结构");
        float[] related = embedder.embed("方法命名不能用单字母或缩写");
        float[] unrelated = embedder.embed("SQL 字符串拼接导致注入风险");

        assertThat(cosine(rule, related)).isGreaterThan(cosine(rule, unrelated) + 0.1);
    }

    @Test
    void englishIsCaseInsensitive() {
        assertThat(cosine(embedder.embed("Method Naming"), embedder.embed("method naming")))
                .isCloseTo(1.0, within(1e-6));
    }

    @Test
    void textWithoutWordFeaturesStillGetsNonZeroVector() {
        assertThat(norm(embedder.embed("!!! ---"))).isCloseTo(1.0, within(1e-6));
    }

    @Test
    void cosineIsNeverNegative() {
        assertThat(cosine(embedder.embed("abc"), embedder.embed("完全无关的中文"))).isGreaterThanOrEqualTo(0);
    }

    private static double norm(float[] v) {
        return Math.sqrt(dot(v, v));
    }

    private static double cosine(float[] a, float[] b) {
        return dot(a, b) / (norm(a) * norm(b));
    }

    private static double dot(float[] a, float[] b) {
        double sum = 0;
        for (int i = 0; i < a.length; i++) {
            sum += (double) a[i] * b[i];
        }
        return sum;
    }
}
