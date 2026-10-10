package com.codemind.review.domain.agent;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link ChunkSummaryJsonParser} 单测（M6 设计审查检查清单：单测验证 ChunkSummary 解析器）。
 */
class ChunkSummaryJsonParserTest {

    private final ChunkSummaryJsonParser parser = new ChunkSummaryJsonParser();

    @Test
    void parsesFullSummaryAlongsideFindings() throws Exception {
        String raw = """
                {"findings": [],
                 "summary": {
                   "changedSymbols": ["RefundClient#submitRefund(long, int)"],
                   "intent": "票据号类型从 String 改为 long",
                   "risks": ["调用方可能未同步"],
                   "crossChunkQuestions": ["谁在调用 submitRefund？"]
                 }}
                """;

        ChunkSummary summary = parser.parse(raw, "chunk-1");

        assertThat(summary.chunkId()).isEqualTo("chunk-1");
        assertThat(summary.changedSymbols()).containsExactly("RefundClient#submitRefund(long, int)");
        assertThat(summary.intent()).isEqualTo("票据号类型从 String 改为 long");
        assertThat(summary.risks()).containsExactly("调用方可能未同步");
        assertThat(summary.crossChunkQuestions()).containsExactly("谁在调用 submitRefund？");
        assertThat(summary.degraded()).isFalse();
        assertThat(summary.isComplete()).isTrue();
    }

    @Test
    void stripsMarkdownFence() throws Exception {
        String raw = """
                ```json
                {"findings": [], "summary": {"changedSymbols": ["A#foo(int)"], "intent": "改 foo"}}
                ```
                """;

        ChunkSummary summary = parser.parse(raw, "chunk-2");

        assertThat(summary.changedSymbols()).containsExactly("A#foo(int)");
        assertThat(summary.risks()).isEmpty();
        assertThat(summary.crossChunkQuestions()).isEmpty();
        assertThat(summary.isComplete()).isTrue();
    }

    @Test
    void rejectsMissingSummaryObject() {
        assertThatThrownBy(() -> parser.parse("{\"findings\": []}", "chunk-1"))
                .isInstanceOf(ChunkSummaryParseException.class)
                .hasMessageContaining("缺少 summary");
    }

    @Test
    void rejectsEmptyChangedSymbols() {
        String raw = "{\"summary\": {\"changedSymbols\": [], \"intent\": \"改了点东西\"}}";

        assertThatThrownBy(() -> parser.parse(raw, "chunk-1"))
                .isInstanceOf(ChunkSummaryParseException.class)
                .hasMessageContaining("changedSymbols");
    }

    @Test
    void rejectsBlankIntent() {
        String raw = "{\"summary\": {\"changedSymbols\": [\"A#foo(int)\"], \"intent\": \"  \"}}";

        assertThatThrownBy(() -> parser.parse(raw, "chunk-1"))
                .isInstanceOf(ChunkSummaryParseException.class)
                .hasMessageContaining("intent");
    }

    @Test
    void rejectsNonJsonAndEmptyInput() {
        assertThatThrownBy(() -> parser.parse("抱歉我无法完成", "chunk-1"))
                .isInstanceOf(ChunkSummaryParseException.class);
        assertThatThrownBy(() -> parser.parse("", "chunk-1"))
                .isInstanceOf(ChunkSummaryParseException.class)
                .hasMessageContaining("空");
    }

    @Test
    void degradedSummaryIsNotComplete() {
        ChunkSummary degraded = ChunkSummary.degraded("chunk-9", "模型没给摘要");

        assertThat(degraded.degraded()).isTrue();
        assertThat(degraded.isComplete()).isFalse();
        assertThat(degraded.renderForPrompt()).contains("本片摘要降级");
    }
}
