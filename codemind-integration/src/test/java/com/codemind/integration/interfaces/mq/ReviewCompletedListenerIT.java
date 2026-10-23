package com.codemind.integration.interfaces.mq;

import com.codemind.common.event.ReviewCompleted;
import com.codemind.common.event.ReviewPublished;
import com.codemind.integration.application.port.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.messaging.handler.annotation.Payload;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * ReviewCompleted → 发布状态机 → ReviewPublished 的真实中间件链路（M7b Task 9）。
 *
 * <p>真实 RocketMQ、Redis、PostgreSQL；写 GitHub 的那个端口换成线程安全的内存实现——不碰真实
 * GitHub（真实写验收是 Task 10 的 {@code -Pgithub-write}）。独立 topic/group，不污染生产链路。
 *
 * <p>前置条件：{@code cd deploy && docker compose up -d}。
 */
@org.springframework.test.annotation.DirtiesContext(classMode = org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(properties = {
        "spring.cloud.nacos.discovery.enabled=false",
        "spring.cloud.discovery.enabled=false",
        "codemind.mq.topic.review-completed=codemind-review-completed-listener-it",
        "codemind.mq.topic.review-published=codemind-review-published-listener-it",
        "codemind.mq.consumer-group.review-completed=codemind-integration-review-completed-listener-it",
        "rocketmq.producer.group=codemind-integration-review-completed-listener-it",
        "codemind.webhook.github-secret=it-webhook-secret"
})
class ReviewCompletedListenerIT {

    private static final String REVIEW_COMPLETED_TOPIC = "codemind-review-completed-listener-it";

    @TestConfiguration
    static class StubScmConfig {
        @Bean
        @Primary
        ScmReviewPublisher fakeScmReviewPublisher() {
            return new InMemoryScmReviewPublisher();
        }

        @Bean
        ReceiptCollector receiptCollector(ObjectMapper objectMapper) {
            return new ReceiptCollector(objectMapper);
        }
    }

    /**
     * 线程安全：创建时记录标记，供查重复用；每个 repo+pr 独立计数。
     * 单例 bean 跨测试方法共享；计数按 repo+pr 隔开，各测试用不同 pr 号，互不干扰。
     */
    static final class InMemoryScmReviewPublisher implements ScmReviewPublisher {
        final Map<String, List<ExistingReview>> reviewsByRepoPr = new ConcurrentHashMap<>();
        private final java.util.concurrent.atomic.AtomicLong nextId = new java.util.concurrent.atomic.AtomicLong(1);

        @Override
        public List<ExistingReview> listReviews(String repo, int pr) {
            return List.copyOf(reviewsByRepoPr.getOrDefault(repo + "#" + pr, List.of()));
        }

        @Override
        public ExistingReview createReview(String repo, int pr, ReviewDraft draft) {
            ExistingReview review = new ExistingReview(nextId.getAndIncrement(), draft.body());
            reviewsByRepoPr.computeIfAbsent(repo + "#" + pr, k -> new CopyOnWriteArrayList<>()).add(review);
            return review;
        }

        int createCount(String repo, int pr) {
            return reviewsByRepoPr.getOrDefault(repo + "#" + pr, List.of()).size();
        }
    }

    /** 独立消费组收集回执，不借用生产 {@code ReviewPublishedListener}（那是 review 服务的职责）。
     * 静态嵌套测试类不会被组件扫描自动发现，必须显式在 {@code StubScmConfig} 里注册成 bean。 */
    @RocketMQMessageListener(topic = "${codemind.mq.topic.review-published}",
            consumerGroup = "codemind-integration-receipt-collector-it")
    static class ReceiptCollector implements org.apache.rocketmq.spring.core.RocketMQListener<String> {
        final List<ReviewPublished> received = new CopyOnWriteArrayList<>();
        private final ObjectMapper objectMapper;
        ReceiptCollector(ObjectMapper objectMapper) { this.objectMapper = objectMapper; }
        @Override
        public void onMessage(@Payload String payload) {
            try {
                received.add(objectMapper.readValue(payload, ReviewPublished.class));
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }
    }

    @Autowired private RocketMQTemplate rocketMQTemplate;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private ScmReviewPublisher scmReviewPublisher;
    @Autowired private JdbcClient jdbcClient;
    @Autowired private ReceiptCollector receiptCollector;

    private final String repo = "codemind/listener-it-" + UUID.randomUUID();
    private final List<String> taskIds = new CopyOnWriteArrayList<>();

    @AfterEach
    void cleanup() {
        for (String taskId : taskIds) {
            jdbcClient.sql("delete from integration.review_publication where task_id = :taskId")
                    .param("taskId", taskId).update();
        }
    }

    @Test
    void completedEventIsConsumedAndPublishedReceiptIsSent() throws Exception {
        var fake = (InMemoryScmReviewPublisher) scmReviewPublisher;
        String taskId = UUID.randomUUID().toString();
        taskIds.add(taskId);
        ReviewCompleted event = ReviewCompleted.of(taskId, repo, 7, "head",
                "summary", List.of(new ReviewCompleted.LineComment("A.java", 11, "issue")));

        rocketMQTemplate.syncSend(REVIEW_COMPLETED_TOPIC, objectMapper.writeValueAsString(event));

        await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(300))
                .untilAsserted(() -> assertThat(fake.createCount(repo, 7)).isEqualTo(1));
        await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(300))
                .untilAsserted(() -> assertThat(receiptCollector.received.stream()
                        .anyMatch(r -> r.taskId().equals(taskId))).isTrue());
    }

    @Test
    void redeliveredEventOnlyCreatesOneReviewAndReceiptIsResent() throws Exception {
        var fake = (InMemoryScmReviewPublisher) scmReviewPublisher;
        String taskId = UUID.randomUUID().toString();
        taskIds.add(taskId);
        ReviewCompleted event = ReviewCompleted.of(taskId, repo, 9, "head",
                "summary", List.of());
        String payload = objectMapper.writeValueAsString(event);

        rocketMQTemplate.syncSend(REVIEW_COMPLETED_TOPIC, payload);
        await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(300))
                .untilAsserted(() -> assertThat(fake.createCount(repo, 9)).isEqualTo(1));
        await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(300))
                .untilAsserted(() -> assertThat(receiptCountFor(taskId)).isGreaterThanOrEqualTo(1L));

        /*
         * S6 评审 I-3（Task 9）：重投必须等到第二条消息真的被处理完（这里用「收到第二份回执」
         * 作为完成信号），而不是只静置几秒看 createCount 没变——那种写法测不出「第二条消息被
         * MQ 悄悄丢弃、根本没投达」这类假绿。已发布状态会在每次重投时重发回执
         * （ReviewPublishService 读到 PUBLISHED 直接走 receipt 分支），所以第二条回执是
         * 「消息真的被消费了」的证据，而不是巧合。
         *
         * S6 fix-round2 评审 R-1：基线用相对增量而非固定值 1——RocketMQ 是 at-least-once，
         * 第一条消息本身也可能被重复消费（例如 rebalance，或 publish 撞上发布锁占用抛异常
         * 触发 reconsume），届时基线一上来就是 2，固定断言 isEqualTo(1) 会在 30s 内永远等
         * 不到，用例超时失败——这跟被测代码是否正确无关，是断言比契约更严。
         */
        long baseline = receiptCountFor(taskId);
        rocketMQTemplate.syncSend(REVIEW_COMPLETED_TOPIC, payload);
        await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(300))
                .untilAsserted(() -> assertThat(receiptCountFor(taskId)).isGreaterThan(baseline));
        assertThat(fake.createCount(repo, 9)).isEqualTo(1);
    }

    private long receiptCountFor(String taskId) {
        return receiptCollector.received.stream().filter(r -> r.taskId().equals(taskId)).count();
    }
}
