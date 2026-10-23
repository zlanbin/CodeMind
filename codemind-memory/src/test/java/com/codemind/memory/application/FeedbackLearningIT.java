package com.codemind.memory.application;

import com.codemind.common.event.FindingFeedbackReceived;
import com.codemind.common.event.FindingFeedbackReceived.FeedbackType;
import com.codemind.common.event.FindingFeedbackReceived.FindingRef;
import com.codemind.memory.domain.feedback.LlmClient;
import com.codemind.memory.domain.memory.MemoryKind;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedDeque;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * 反馈学习在真实 PG + 真实 RocketMQ 上的全链路（M9 T5）。LLM 换成按 prompt 类型出队的脚本桩：
 * 抽取与冲突消解各一条队列，断言的是「LLM 给出某个决定后，记忆库最终是什么样」。
 */
@SpringBootTest
class FeedbackLearningIT {

    private static final String FP = "e".repeat(64);

    @TestConfiguration
    static class StubLlmConfig {
        @Bean
        @Primary
        ScriptedLlm scriptedLlm() {
            return new ScriptedLlm();
        }
    }

    /** 抽取 prompt 含「概括成可复用的项目规则」，冲突消解 prompt 含「选择一个动作」。 */
    static final class ScriptedLlm implements LlmClient {
        final ConcurrentLinkedDeque<String> extract = new ConcurrentLinkedDeque<>();
        final ConcurrentLinkedDeque<String> conflict = new ConcurrentLinkedDeque<>();

        @Override
        public String complete(String systemPrompt, String userPrompt) throws LlmException {
            String next = systemPrompt.contains("选择一个动作") ? conflict.poll() : extract.poll();
            if (next == null) {
                throw new LlmException("脚本用尽");
            }
            return next;
        }
    }

    @Autowired FeedbackLearningService service;
    @Autowired MemoryService memoryService;
    @Autowired ScriptedLlm llm;
    @Autowired JdbcClient jdbc;
    @Autowired RocketMQTemplate rocketMQTemplate;
    @Autowired ObjectMapper objectMapper;
    @Value("${codemind.mq.topic.finding-feedback}") String topic;

    private final String project = "codemind/feedback-learning-it-" + UUID.randomUUID();

    @BeforeEach
    void resetScript() {
        llm.extract.clear();
        llm.conflict.clear();
    }

    @AfterEach
    void cleanUp() {
        jdbc.sql("delete from memory.memory where project = :p").param("p", project).update();
        jdbc.sql("delete from memory.feedback_event where project = :p").param("p", project).update();
    }

    @Test
    void thumbsDownAddsRejectedPatternOnceEvenWhenReplayed() throws Exception {
        llm.extract.add(rules("本项目允许 Controller 直接返回实体"));
        FindingFeedbackReceived event = event(FeedbackType.THUMBS_DOWN, null);

        var first = service.learn(event);
        var replay = service.learn(event);

        assertThat(first.status()).isEqualTo(FeedbackLearningService.Status.LEARNED);
        assertThat(first.actions()).singleElement().asString().startsWith("ADD#");
        assertThat(replay.status()).isEqualTo(FeedbackLearningService.Status.ALREADY_PROCESSED);
        assertThat(memories()).containsExactly(Map.of("kind", "REJECTED_PATTERN",
                "content", "[DESIGN] 本项目允许 Controller 直接返回实体", "importance", 0.8));
    }

    @Test
    void updateRewritesTheRelatedPatternInPlaceOfTheOld() throws Exception {
        long old = memoryService.write(project, MemoryKind.REJECTED_PATTERN, "[DESIGN] 允许 Controller 返回实体", 0.6)
                .memory().id();
        llm.extract.add(rules("本项目允许 Controller 直接返回实体"));
        llm.conflict.add("{\"action\": \"UPDATE\", \"targetId\": " + old
                + ", \"content\": \"[DESIGN] 本项目 Controller 可以直接返回实体，不必转 DTO\"}");

        var outcome = service.learn(event(FeedbackType.REPLY, "不适用，因为本项目约定直接返回实体"));

        assertThat(outcome.actions()).singleElement().asString().startsWith("UPDATE#" + old + "->");
        assertThat(memories()).containsExactly(Map.of("kind", "REJECTED_PATTERN",
                "content", "[DESIGN] 本项目 Controller 可以直接返回实体，不必转 DTO", "importance", 0.9));
    }

    @Test
    void deleteRemovesContradictingDefectAndAddsTheRejection() throws Exception {
        long defect = memoryService.write(project, MemoryKind.COMMON_DEFECT, "[DESIGN] Controller 直接返回实体是缺陷", 0.7)
                .memory().id();
        llm.extract.add(rules("本项目允许 Controller 直接返回实体"));
        llm.conflict.add("{\"action\": \"DELETE\", \"targetId\": " + defect + "}");

        service.learn(event(FeedbackType.THUMBS_DOWN, null));

        assertThat(memories()).containsExactly(Map.of("kind", "REJECTED_PATTERN",
                "content", "[DESIGN] 本项目允许 Controller 直接返回实体", "importance", 0.8));
    }

    @Test
    void replyThatIsNotARejectionIsRecordedButLearnsNothing() throws Exception {
        llm.extract.add("{\"rejected\": false, \"rules\": []}");

        var outcome = service.learn(event(FeedbackType.REPLY, "谢谢，已改"));

        assertThat(outcome.status()).isEqualTo(FeedbackLearningService.Status.NOT_REJECTED);
        assertThat(memories()).isEmpty();
        assertThat(jdbc.sql("select outcome from memory.feedback_event where project = :p")
                .param("p", project).query(String.class).list()).containsExactly("NOT_REJECTED");
    }

    @Test
    void thumbsDownWithLlmDownLearnsTemplateAndTagsOutcomeAsDegraded() throws Exception {
        // 脚本为空 = LLM 每次都失败；👎 走模板
        var outcome = service.learn(event(FeedbackType.THUMBS_DOWN, null));

        assertThat(outcome.degraded()).isTrue();
        assertThat(memories()).singleElement().satisfies(m -> {
            assertThat(m.get("kind")).isEqualTo("REJECTED_PATTERN");
            assertThat((String) m.get("content")).startsWith("[DESIGN] 开发者否决过这类意见：");
        });
        assertThat(jdbc.sql("select outcome from memory.feedback_event where project = :p")
                .param("p", project).query(String.class).single()).startsWith("DEGRADED:ADD#");
    }

    @Test
    void noopOnADefectIsRefusedSoARejectionIsStillWritten() throws Exception {
        long defect = memoryService.write(project, MemoryKind.COMMON_DEFECT, "[DESIGN] Controller 直接返回实体是缺陷", 0.7)
                .memory().id();
        llm.extract.add(rules("本项目允许 Controller 直接返回实体"));
        llm.conflict.add("{\"action\": \"NOOP\", \"targetId\": " + defect + "}");

        service.learn(event(FeedbackType.THUMBS_DOWN, null));

        assertThat(memories()).extracting(m -> m.get("kind"), m -> m.get("importance"))
                .containsExactly(org.assertj.core.groups.Tuple.tuple("COMMON_DEFECT", 0.7),
                        org.assertj.core.groups.Tuple.tuple("REJECTED_PATTERN", 0.8));
    }

    @Test
    void thumbsDownOnAMergedCommentIsAmbiguousAndLearnsNothing() throws Exception {
        llm.extract.add(rules("不应被调用"));
        FindingFeedbackReceived merged = FindingFeedbackReceived.of(project, 2, 70L, FeedbackType.THUMBS_DOWN, 77L,
                "lan-bin", null, "四条意见", List.of(new FindingRef(FP, "CORRECTNESS"),
                        new FindingRef("f".repeat(64), "SECURITY")));

        var outcome = service.learn(merged);

        assertThat(outcome.status()).isEqualTo(FeedbackLearningService.Status.AMBIGUOUS);
        assertThat(memories()).isEmpty();
        assertThat(llm.extract).hasSize(1);  // 没调 LLM
        assertThat(service.learn(merged).status()).isEqualTo(FeedbackLearningService.Status.ALREADY_PROCESSED);
    }

    @Test
    void eventArrivingOverMqIsLearned() throws Exception {
        llm.extract.add(rules("本项目允许 Controller 直接返回实体"));
        FindingFeedbackReceived event = event(FeedbackType.THUMBS_DOWN, null);

        rocketMQTemplate.syncSend(topic, objectMapper.writeValueAsString(event));

        await().atMost(Duration.ofSeconds(60)).pollInterval(Duration.ofMillis(500))
                .untilAsserted(() -> assertThat(memories()).hasSize(1));
        assertThat(jdbc.sql("select count(*) from memory.feedback_event where event_id = :id")
                .param("id", event.eventId()).query(Integer.class).single()).isEqualTo(1);
    }

    private List<Map<String, Object>> memories() {
        return jdbc.sql("select kind, content, importance from memory.memory where project = :p order by id")
                .param("p", project).query().listOfRows();
    }

    private static String rules(String rule) {
        return "{\"rejected\": true, \"rules\": [{\"category\": \"DESIGN\", \"rule\": \"" + rule + "\"}]}";
    }

    private FindingFeedbackReceived event(FeedbackType type, String reply) {
        return FindingFeedbackReceived.of(project, 2, 70L, type, Math.abs(UUID.randomUUID().getMostSignificantBits()) + 1,
                "lan-bin", reply, "描述：Controller 直接返回了实体，建议转 DTO", List.of(new FindingRef(FP, "DESIGN")));
    }
}
