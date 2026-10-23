package com.codemind.review.interfaces.mq;

import com.codemind.common.event.ReviewPublished;
import com.codemind.review.application.ReviewCompletionService;
import com.codemind.review.application.ReviewPublicationService;
import com.codemind.review.domain.publication.ReviewOutboxRepository;
import com.codemind.review.domain.task.*;
import com.codemind.review.infrastructure.persistence.JdbcReviewOutboxRepository;
import com.codemind.review.infrastructure.persistence.JdbcReviewTaskRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * 真实 MQ + PG：发 {@code ReviewPublished} → {@link ReviewPublishedListener} → 推进任务状态
 * （M7b Task 9）。独立 topic/group，不碰生产链路。
 *
 * <p>S6 评审 M-2 更正：本类的切片 {@code Config} 没有 {@code @Import}
 * {@code PublicationSchedulingConfig}，上下文里压根不存在 dispatcher bean——不是「关了自动调度」，
 * 是这个最小上下文从没装过它。本 IT 测的是 {@code ReviewPublishedListener} 收到回执后的行为，
 * 不涉及 outbox 投递那一侧。
 *
 * <p>前置条件：{@code cd deploy && docker compose up -d}。
 */
@org.springframework.test.annotation.DirtiesContext(classMode = org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(classes = ReviewPublishedListenerIT.Config.class, properties = {
        "spring.cloud.nacos.discovery.enabled=false", "spring.cloud.discovery.enabled=false",
        "spring.autoconfigure.exclude=org.springframework.ai.model.openai.autoconfigure.OpenAiChatAutoConfiguration,org.springframework.ai.model.openai.autoconfigure.OpenAiEmbeddingAutoConfiguration",
        "codemind.mq.topic.review-published=codemind-review-published-listener-it",
        "codemind.mq.consumer-group.review-published=codemind-review-review-published-listener-it",
        "rocketmq.producer.group=codemind-review-review-published-listener-it"
})
class ReviewPublishedListenerIT {

    private static final String TOPIC = "codemind-review-published-listener-it";

    @Configuration
    @EnableAutoConfiguration
    @Import({JdbcReviewTaskRepository.class, JdbcReviewOutboxRepository.class, ReviewCompletionService.class,
            ReviewPublicationService.class, ReviewPublishedListener.class})
    static class Config { }

    @Autowired private ReviewTaskRepository tasks;
    @Autowired private ReviewOutboxRepository outbox;
    @Autowired private ReviewCompletionService completion;
    @Autowired private RocketMQTemplate rocketMQTemplate;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcClient jdbcClient;

    private final java.util.List<String> taskIds = new java.util.concurrent.CopyOnWriteArrayList<>();

    @AfterEach
    void cleanup() {
        for (String taskId : taskIds) {
            jdbcClient.sql("delete from review_publication_outbox where task_id=:id")
                    .param("id", UUID.fromString(taskId)).update();
            jdbcClient.sql("delete from review_task where task_id=:id")
                    .param("id", UUID.fromString(taskId)).update();
        }
    }

    @Test
    void receiptAdvancesAggregatingTaskWithOutboxToPublishedAndAcknowledges() throws Exception {
        String taskId = registerAggregatingTaskWithOutbox();
        ReviewPublished event = ReviewPublished.of(taskId, 2, false);

        rocketMQTemplate.syncSend(TOPIC, objectMapper.writeValueAsString(event));

        await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(300))
                .untilAsserted(() -> assertThat(tasks.findById(taskId).orElseThrow().status())
                        .isEqualTo(ReviewTaskStatus.PUBLISHED));
        await().atMost(Duration.ofSeconds(10)).pollInterval(Duration.ofMillis(300))
                .untilAsserted(() -> assertThat(outbox.findByTaskId(taskId).orElseThrow().acknowledgedAt())
                        .isNotNull());
    }

    @Test
    void redeliveredReceiptDoesNotChangeAlreadyPublishedTask() throws Exception {
        String taskId = registerAggregatingTaskWithOutbox();
        ReviewPublished event = ReviewPublished.of(taskId, 1, false);
        String payload = objectMapper.writeValueAsString(event);

        rocketMQTemplate.syncSend(TOPIC, payload);
        await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(300))
                .untilAsserted(() -> assertThat(tasks.findById(taskId).orElseThrow().status())
                        .isEqualTo(ReviewTaskStatus.PUBLISHED));

        rocketMQTemplate.syncSend(TOPIC, payload);
        await().during(Duration.ofSeconds(3)).atMost(Duration.ofSeconds(10))
                .pollInterval(Duration.ofMillis(300))
                .untilAsserted(() -> assertThat(tasks.findById(taskId).orElseThrow().status())
                        .isEqualTo(ReviewTaskStatus.PUBLISHED));
    }

    @Test
    void receiptForTaskStillReviewingDoesNotAdvanceIt() throws Exception {
        ReviewTask task = ReviewTask.create("codemind/published-it-" + UUID.randomUUID(), 7,
                "head", "base", UUID.randomUUID().toString());
        assertThat(tasks.insertIfAbsent(task)).isTrue();
        taskIds.add(task.taskId());
        tasks.updateStatus(task.taskId(), ReviewTaskStatus.REVIEWING);

        rocketMQTemplate.syncSend(TOPIC, objectMapper.writeValueAsString(ReviewPublished.of(task.taskId(), 0, false)));

        await().during(Duration.ofSeconds(3)).atMost(Duration.ofSeconds(10))
                .pollInterval(Duration.ofMillis(300))
                .untilAsserted(() -> assertThat(tasks.findById(task.taskId()).orElseThrow().status())
                        .isEqualTo(ReviewTaskStatus.REVIEWING));
    }

    private String registerAggregatingTaskWithOutbox() {
        ReviewTask task = ReviewTask.create("codemind/published-it-" + UUID.randomUUID(), 7,
                "head", "base", UUID.randomUUID().toString());
        assertThat(tasks.insertIfAbsent(task)).isTrue();
        taskIds.add(task.taskId());
        tasks.updateStatus(task.taskId(), ReviewTaskStatus.AGGREGATING);
        completion.register(com.codemind.common.event.ReviewCompleted.of(task.taskId(), task.repo(),
                task.prNumber(), task.headSha(), "summary", List.of()));
        return task.taskId();
    }
}
