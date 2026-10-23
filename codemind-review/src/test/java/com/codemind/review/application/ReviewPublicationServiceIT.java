package com.codemind.review.application;

import com.codemind.common.event.ReviewCompleted;
import com.codemind.common.event.ReviewPublished;
import com.codemind.review.domain.publication.ReviewOutboxRepository;
import com.codemind.review.domain.task.ReviewTask;
import com.codemind.review.domain.task.ReviewTaskRepository;
import com.codemind.review.domain.task.ReviewTaskStatus;
import com.codemind.review.infrastructure.persistence.JdbcReviewOutboxRepository;
import com.codemind.review.infrastructure.persistence.JdbcReviewTaskRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;

/**
 * {@link ReviewPublicationService} 真实 PG IT（S6 评审 I-1/I-2：原来只有假事务管理器的单测，
 * 「事务中途故障全部回滚」这条验收点无法用那个替身证明）。
 *
 * <p>被测服务是容器里真实装配的 bean（构造参数里的 outbox 已被 {@link MockitoSpyBean} 替换成 spy），
 * 不在每条用例里手造实例——S6 评审 N-6 指出手造会让 {@code @Import} 的那个 bean 空置。
 */
@SpringBootTest(classes = ReviewPublicationServiceIT.Config.class, properties = {
        "spring.cloud.nacos.discovery.enabled=false", "spring.cloud.discovery.enabled=false",
        "spring.autoconfigure.exclude=org.apache.rocketmq.spring.autoconfigure.RocketMQAutoConfiguration,org.springframework.ai.model.openai.autoconfigure.OpenAiChatAutoConfiguration,org.springframework.ai.model.openai.autoconfigure.OpenAiEmbeddingAutoConfiguration"
})
class ReviewPublicationServiceIT {
    @Configuration
    @EnableAutoConfiguration
    @Import({JdbcReviewTaskRepository.class, JdbcReviewOutboxRepository.class,
            ReviewCompletionService.class, ReviewPublicationService.class})
    static class Config { }

    @Autowired private ReviewTaskRepository tasks;
    @Autowired private ReviewCompletionService completion;
    @Autowired private ReviewPublicationService service;
    @Autowired private JdbcClient jdbc;
    @Autowired private ObjectMapper objectMapper;
    @MockitoSpyBean private ReviewOutboxRepository outbox;
    private final List<String> ids = new ArrayList<>();

    @AfterEach
    void cleanup() {
        reset(outbox);
        for (String id : ids) {
            jdbc.sql("delete from review_publication_outbox where task_id=:id").param("id", UUID.fromString(id)).update();
            jdbc.sql("delete from review_task where task_id=:id").param("id", UUID.fromString(id)).update();
        }
    }

    @Test
    void successfulReceiptPublishesAndAcknowledgesInOneTransaction() {
        String taskId = taskWithOutbox();

        assertTrue(service.markPublished(ReviewPublished.of(taskId, 2, false)));

        assertEquals(ReviewTaskStatus.PUBLISHED, tasks.findById(taskId).orElseThrow().status());
        assertNotNull(outbox.findByTaskId(taskId).orElseThrow().acknowledgedAt());
    }

    @Test
    void repeatedReceiptOnAlreadyPublishedTaskStaysIdempotent() {
        String taskId = taskWithOutbox();
        service.markPublished(ReviewPublished.of(taskId, 1, false));

        assertTrue(service.markPublished(ReviewPublished.of(taskId, 1, false)));
        assertEquals(ReviewTaskStatus.PUBLISHED, tasks.findById(taskId).orElseThrow().status());
    }

    @Test
    void unknownTaskOrMissingOutboxIsNotAcknowledged() {
        assertFalse(service.markPublished(ReviewPublished.of(UUID.randomUUID().toString(), 0, false)));
        verify(outbox, never()).acknowledge(any(), any());
    }

    /**
     * 验收点「事务中途故障全部回滚」：ACK 之前 outbox.acknowledge 本身抛异常，
     * 真实 PG 事务必须整体回滚——任务不能停在 PUBLISHED 却没有 ACK 的半完成状态。
     */
    @Test
    void midTransactionFailureRollsBackBothStatusAdvanceAndAcknowledge() {
        String taskId = taskWithOutbox();
        doThrow(new IllegalStateException("simulated ack failure")).when(outbox).acknowledge(eq(taskId), any());

        assertThrows(IllegalStateException.class, () -> service.markPublished(ReviewPublished.of(taskId, 1, false)));

        // 回滚后状态必须还是 AGGREGATING，不能停在「已推进但未确认」的半完成状态
        assertEquals(ReviewTaskStatus.AGGREGATING, tasks.findById(taskId).orElseThrow().status());
        reset(outbox);
        assertNull(outbox.findByTaskId(taskId).orElseThrow().acknowledgedAt());
    }

    /**
     * S6 评审 N-1：原版本没插 outbox 行，markPublished 在「原请求缺失」分支就直接返回 false，
     * 根本没走到状态判定——跟 unknownTaskOrMissingOutboxIsNotAcknowledged 测的是同一个分支，
     * 用例名声称覆盖「状态不匹配」却实际没覆盖。{@code register} 要求 AGGREGATING 前置，
     * 这里任务是 REVIEWING，没法走 register，只能裸插一条 outbox 行来绕开第一个分支，
     * 让断言真正落在状态判定上。
     */
    @Test
    void reviewingTaskReceiptIsRejectedByStatusCheck() {
        ReviewTask task = ReviewTask.create("codemind/publication-it-" + UUID.randomUUID(), 7,
                "head", "base", UUID.randomUUID().toString());
        assertTrue(tasks.insertIfAbsent(task));
        ids.add(task.taskId());
        tasks.updateStatus(task.taskId(), ReviewTaskStatus.REVIEWING);
        insertOutboxRowDirectly(task);

        assertFalse(service.markPublished(ReviewPublished.of(task.taskId(), 0, false)));
        assertEquals(ReviewTaskStatus.REVIEWING, tasks.findById(task.taskId()).orElseThrow().status());
        verify(outbox, never()).acknowledge(any(), any());
    }

    private void insertOutboxRowDirectly(ReviewTask task) {
        try {
            var event = ReviewCompleted.of(task.taskId(), task.repo(), task.prNumber(),
                    task.headSha(), "summary", List.of());
            jdbc.sql("""
                    insert into review_publication_outbox(task_id,event_id,payload)
                    values (:taskId,:eventId,cast(:payload as jsonb))
                    """).param("taskId", UUID.fromString(task.taskId()))
                    .param("eventId", UUID.fromString(event.eventId()))
                    .param("payload", objectMapper.writeValueAsString(event)).update();
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private String taskWithOutbox() {
        ReviewTask task = ReviewTask.create("codemind/publication-it-" + UUID.randomUUID(), 7,
                "head", "base", UUID.randomUUID().toString());
        assertTrue(tasks.insertIfAbsent(task));
        ids.add(task.taskId());
        tasks.updateStatus(task.taskId(), ReviewTaskStatus.AGGREGATING);
        completion.register(ReviewCompleted.of(task.taskId(), task.repo(), task.prNumber(),
                task.headSha(), "summary", List.of()));
        return task.taskId();
    }
}
