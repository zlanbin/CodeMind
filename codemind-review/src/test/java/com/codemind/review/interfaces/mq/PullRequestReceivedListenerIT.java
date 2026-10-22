package com.codemind.review.interfaces.mq;

import com.codemind.common.event.PullRequestReceived;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * MQ → 落库链路：真实 RocketMQ 发消息，真实 PostgreSQL 落 ReviewTask。
 *
 * <p>覆盖 M1 验证点「同一 webhook 重放 2 次只生成 1 个 ReviewTask」中 review 侧的部分：
 * 同一 PR 的同一 headSha 重复投递，只落一行。
 *
 * <p>前置条件：{@code cd deploy && docker compose up -d}。
 */
@SpringBootTest(properties = {
        "spring.cloud.nacos.discovery.enabled=false",
        "spring.cloud.discovery.enabled=false",
        // 独立 topic 与消费组，不污染业务链路
        "codemind.mq.topic.pull-request-received=codemind-pull-request-received-it",
        "codemind.mq.consumer-group.pull-request-received=codemind-review-pr-received-it"
})
class PullRequestReceivedListenerIT {

    private static final String TOPIC = "codemind-pull-request-received-it";

    @Autowired
    private RocketMQTemplate rocketMQTemplate;

    @Autowired
    private JdbcClient jdbcClient;

    @Autowired
    private ObjectMapper objectMapper;

    /** 每轮用独立 repo 名，避免历史消息干扰计数。 */
    private final String repo = "codemind/it-mq-" + UUID.randomUUID();

    @AfterEach
    void cleanUp() {
        jdbcClient.sql("delete from review_task where repo = :repo")
                .param("repo", repo)
                .update();
    }

    /**
     * M1 原先还断言状态为 {@code CREATED}，M7a 起不再断言：建完任务就异步触发评审
     * （{@code ReviewExecutionService.submitAsync}），状态会立刻往前走——integration 可达时
     * 是 {@code PLANNING} 及之后，不可达时是 {@code FAILED}。本 IT 要验的是「落一行且身份正确」，
     * 状态流转由 {@code CrossSessionMapReduceTest} 与 {@code RealPullRequestReviewIT} 覆盖。
     */
    @Test
    void 收到事件后落一条任务且身份字段正确() throws Exception {
        send(PullRequestReceived.of("delivery-" + UUID.randomUUID(), "opened",
                repo, 7, "headsha", "basesha"));

        await().atMost(Duration.ofSeconds(30))
                .pollInterval(Duration.ofMillis(300))
                .untilAsserted(() -> assertThat(countRows()).isEqualTo(1));

        assertThat(prNumberOf()).isEqualTo(7);
        assertThat(headShaOf()).isEqualTo("headsha");
    }

    @Test
    void 同一PR同一headSha重复投递只落一行() throws Exception {
        String deliveryId = "delivery-" + UUID.randomUUID();
        // 模拟 webhook 重放后 Redis 幂等键已过期的情况：事件被投递两次
        PullRequestReceived first = PullRequestReceived.of(deliveryId, "opened",
                repo, 7, "headsha", "basesha");
        PullRequestReceived second = PullRequestReceived.of(deliveryId, "opened",
                repo, 7, "headsha", "basesha");

        send(first);
        send(second);

        // 先等到至少一行，再静置确认不会变成两行
        await().atMost(Duration.ofSeconds(30))
                .pollInterval(Duration.ofMillis(300))
                .untilAsserted(() -> assertThat(countRows()).isEqualTo(1));
        await().during(Duration.ofSeconds(3))
                .atMost(Duration.ofSeconds(10))
                .pollInterval(Duration.ofMillis(300))
                .untilAsserted(() -> assertThat(countRows()).isEqualTo(1));
    }

    @Test
    void 同PR不同headSha落两行() throws Exception {
        send(PullRequestReceived.of("d1-" + UUID.randomUUID(), "opened", repo, 7, "sha-1", "basesha"));
        send(PullRequestReceived.of("d2-" + UUID.randomUUID(), "synchronize", repo, 7, "sha-2", "basesha"));

        await().atMost(Duration.ofSeconds(30))
                .pollInterval(Duration.ofMillis(300))
                .untilAsserted(() -> assertThat(countRows()).isEqualTo(2));
    }

    private void send(PullRequestReceived event) throws Exception {
        rocketMQTemplate.syncSend(TOPIC, objectMapper.writeValueAsString(event));
    }

    private int countRows() {
        return jdbcClient.sql("select count(*) from review_task where repo = :repo")
                .param("repo", repo)
                .query(Integer.class)
                .single();
    }

    private int prNumberOf() {
        return jdbcClient.sql("select pr_number from review_task where repo = :repo")
                .param("repo", repo)
                .query(Integer.class)
                .single();
    }

    private String headShaOf() {
        return jdbcClient.sql("select head_sha from review_task where repo = :repo")
                .param("repo", repo)
                .query(String.class)
                .single();
    }
}
