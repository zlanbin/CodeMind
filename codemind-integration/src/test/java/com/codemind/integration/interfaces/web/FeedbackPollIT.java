package com.codemind.integration.interfaces.web;

import com.codemind.common.event.FindingFeedbackReceived;
import com.codemind.common.event.FindingMarker;
import com.codemind.integration.application.port.ScmFeedbackReader;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 手动轮询端点 → 账本 → 真实 RocketMQ（M9 T4）。GitHub 读端口换成桩，不碰真实 GitHub；
 * 独立 topic 与消费组，用独立收集器确认事件真的进了 MQ。
 */
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(properties = {
        "spring.cloud.nacos.discovery.enabled=false",
        "spring.cloud.discovery.enabled=false",
        "codemind.mq.topic.finding-feedback=codemind-finding-feedback-poll-it",
        "rocketmq.producer.group=codemind-integration-feedback-poll-it",
        "codemind.webhook.github-secret=it-webhook-secret"
})
@AutoConfigureMockMvc
class FeedbackPollIT {

    private static final String FP = "d".repeat(64);
    private static final long REVIEW_ID = 424242;

    @TestConfiguration
    static class StubConfig {
        @Bean
        @Primary
        StubFeedbackReader stubFeedbackReader() {
            return new StubFeedbackReader();
        }

        @Bean
        FeedbackCollector feedbackCollector(ObjectMapper objectMapper) {
            return new FeedbackCollector(objectMapper);
        }
    }

    static final class StubFeedbackReader implements ScmFeedbackReader {
        volatile List<ReviewCommentView> comments = List.of();
        volatile List<ReactionView> reactions = List.of();

        @Override
        public boolean canWrite(String repo, String login) {
            return "lan-bin".equals(login);
        }

        @Override
        public List<ReviewCommentView> listReviewComments(String repo, int pr) {
            return comments;
        }

        @Override
        public List<ReactionView> listThumbsDown(String repo, long commentId) {
            return reactions;
        }
    }

    @RocketMQMessageListener(topic = "${codemind.mq.topic.finding-feedback}",
            consumerGroup = "codemind-integration-feedback-collector-it")
    static class FeedbackCollector implements RocketMQListener<String> {
        final List<FindingFeedbackReceived> received = new CopyOnWriteArrayList<>();
        private final ObjectMapper objectMapper;

        FeedbackCollector(ObjectMapper objectMapper) {
            this.objectMapper = objectMapper;
        }

        @Override
        public void onMessage(String payload) {
            try {
                received.add(objectMapper.readValue(payload, FindingFeedbackReceived.class));
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;
    @Autowired StubFeedbackReader reader;
    @Autowired FeedbackCollector collector;

    private final String repo = "codemind/feedback-poll-it-" + UUID.randomUUID();
    private final String taskId = "feedback-poll-it-" + UUID.randomUUID();

    @AfterEach
    void cleanUp() {
        jdbc.sql("delete from integration.finding_feedback where repo = :r").param("r", repo).update();
        jdbc.sql("delete from integration.review_publication where task_id = :t").param("t", taskId).update();
    }

    @Test
    void pollRegistersThumbsDownSendsItOnceAndRepollIsNoop() throws Exception {
        jdbc.sql("""
                insert into integration.review_publication
                    (task_id, event_payload, content_digest, repo, pr_number, head_sha, state, mode,
                     review_id, inline_count, degraded)
                values (:t, '{}'::jsonb, :d, :r, 2, 'h', 'PUBLISHED', 'INLINE', :rid, 1, false)
                """).param("t", taskId).param("d", "0".repeat(64)).param("r", repo).param("rid", REVIEW_ID).update();
        reader.comments = List.of(new ScmFeedbackReader.ReviewCommentView(70, REVIEW_ID, null, "codemind-bot",
                "描述：越界\n\n" + FindingMarker.format(FP, "CORRECTNESS")));
        reader.reactions = List.of(new ScmFeedbackReader.ReactionView(71, "lan-bin"));

        mvc.perform(post("/internal/feedback/poll").param("repo", repo).param("prNumber", "2"))
                .andExpect(status().isOk());
        mvc.perform(post("/internal/feedback/poll").param("repo", repo).param("prNumber", "2"))
                .andExpect(status().isOk());

        assertThat(jdbc.sql("select state from integration.finding_feedback where repo = :r")
                .param("r", repo).query(String.class).list()).containsExactly("SENT");
        await().atMost(Duration.ofSeconds(60)).pollInterval(Duration.ofMillis(500))
                .untilAsserted(() -> assertThat(collector.received.stream().filter(e -> e.repo().equals(repo)))
                        .singleElement()
                        .satisfies(e -> {
                            assertThat(e.commentId()).isEqualTo(70);
                            assertThat(e.findings()).extracting(FindingFeedbackReceived.FindingRef::fingerprint)
                                    .containsExactly(FP);
                        }));
    }

    @Test
    void invalidParametersAreRejected() throws Exception {
        mvc.perform(post("/internal/feedback/poll").param("repo", repo).param("prNumber", "0"))
                .andExpect(status().isBadRequest());
    }
}
