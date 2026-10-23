package com.codemind.memory.domain.feedback;

import com.codemind.memory.domain.feedback.FeedbackExtractor.ExtractionUnavailableException;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FeedbackExtractorTest {

    private final ScriptedLlm llm = new ScriptedLlm();
    private final FeedbackExtractor extractor = new FeedbackExtractor(llm, 1);

    private static FeedbackInput thumbsDown() {
        return new FeedbackInput("o/r", true, null, "描述：Controller 直接返回了实体", List.of("DESIGN"));
    }

    private static FeedbackInput reply(String text) {
        return new FeedbackInput("o/r", false, text, "描述：Controller 直接返回了实体", List.of("DESIGN", "STYLE"));
    }

    @Test
    void parsesRulesWithFencesAndCategoryNormalization() throws Exception {
        llm.replies.add("""
                ```json
                {"rejected": true, "rules": [{"category": "design", "rule": " 本项目允许 Controller 直接返回实体 "},
                                             {"category": "SECURITY", "rule": "不必为内部接口加鉴权提示"}]}
                ```""");

        Lessons lessons = extractor.extract(reply("不适用，因为本项目约定直接返回实体"));

        assertThat(lessons.rejected()).isTrue();
        assertThat(lessons.degraded()).isFalse();
        // 评论里没有 SECURITY 维度：编出来的规则整条丢弃，不归到别的维度
        assertThat(lessons.rules()).containsExactly(new Lessons.Rule("DESIGN", "本项目允许 Controller 直接返回实体"));
        assertThat(lessons.rules().get(0).memoryContent()).isEqualTo("[DESIGN] 本项目允许 Controller 直接返回实体");
        assertThat(llm.userPrompts.get(0)).contains("不适用，因为本项目约定直接返回实体", "DESIGN, STYLE",
                "Controller 直接返回了实体");
    }

    @Test
    void replyThatIsNotARejectionLearnsNothing() throws Exception {
        llm.replies.add("{\"rejected\": false, \"rules\": []}");

        assertThat(extractor.extract(reply("谢谢，已改")).rejected()).isFalse();
    }

    @Test
    void thumbsDownIsAlwaysARejectionEvenIfModelDisagrees() throws Exception {
        llm.replies.add("{\"rejected\": false, \"rules\": []}");

        Lessons lessons = extractor.extract(thumbsDown());

        assertThat(lessons.rejected()).isTrue();
        assertThat(lessons.degraded()).isTrue();
        assertThat(lessons.rules()).singleElement()
                .satisfies(r -> assertThat(r.rule()).contains("Controller 直接返回了实体"));
    }

    @Test
    void malformedOutputIsRetriedThenSucceeds() throws Exception {
        llm.replies.add("不是 JSON");
        llm.replies.add("{\"rejected\": true, \"rules\": [{\"category\": \"DESIGN\", \"rule\": \"规则\"}]}");

        assertThat(extractor.extract(reply("不适用")).rules()).hasSize(1);
        assertThat(llm.calls).isEqualTo(2);
    }

    @Test
    void exhaustedRetriesFallBackToTemplateForThumbsDownButFailForReply() {
        llm.failAlways = true;

        assertThatThrownBy(() -> extractor.extract(reply("不适用"))).isInstanceOf(ExtractionUnavailableException.class);
        assertThat(llm.calls).isEqualTo(2);

        Lessons fallback;
        try {
            fallback = extractor.extract(new FeedbackInput("o/r", true, null, "x".repeat(500), List.of("DESIGN", "DESIGN", "TEST")));
        } catch (ExtractionUnavailableException e) {
            throw new AssertionError(e);
        }
        assertThat(fallback.degraded()).isTrue();
        assertThat(fallback.rules()).extracting(Lessons.Rule::category).containsExactly("DESIGN", "TEST");
        assertThat(fallback.rules().get(0).rule()).hasSizeLessThan(230);
    }

    @Test
    void rejectsStructurallyInvalidRules() {
        List<String> allowed = List.of("DESIGN");
        assertThatThrownBy(() -> FeedbackExtractor.parse("{\"rejected\": true, \"rules\": []}", allowed))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> FeedbackExtractor.parse("{\"rejected\": true, \"rules\": [{\"category\": \"DESIGN\"}]}", allowed))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> FeedbackExtractor.parse("{\"rejected\": \"yes\", \"rules\": []}", allowed))
                .isInstanceOf(IllegalArgumentException.class);
        // 全部规则维度都是编的：等同非法输出
        assertThatThrownBy(() -> FeedbackExtractor.parse(
                "{\"rejected\": true, \"rules\": [{\"category\": \"SECURITY\", \"rule\": \"x\"}]}", allowed))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> FeedbackExtractor.parse("{\"rejected\": true, \"rules\": [{\"category\": \"DESIGN\", \"rule\": \""
                + "长".repeat(301) + "\"}]}", allowed)).isInstanceOf(IllegalArgumentException.class);
    }

    static final class ScriptedLlm implements LlmClient {
        final Deque<String> replies = new ArrayDeque<>();
        final List<String> userPrompts = new java.util.ArrayList<>();
        boolean failAlways;
        int calls;

        @Override
        public String complete(String systemPrompt, String userPrompt) throws LlmException {
            calls++;
            userPrompts.add(userPrompt);
            if (failAlways || replies.isEmpty()) {
                throw new LlmException("down");
            }
            return replies.poll();
        }
    }
}
