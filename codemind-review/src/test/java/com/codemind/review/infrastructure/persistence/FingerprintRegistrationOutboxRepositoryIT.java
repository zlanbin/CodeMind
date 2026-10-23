package com.codemind.review.infrastructure.persistence;

import com.codemind.review.domain.publication.FingerprintRegistrationOutboxRepository;
import com.codemind.review.domain.task.ReviewTask;
import com.codemind.review.domain.task.ReviewTaskRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 仓储与 SKIP LOCKED 领取/租约语义，跑在真实 PostgreSQL 上（Flyway 已建表，M10 V7）。
 *
 * <p>前置条件：{@code cd deploy && docker compose up -d}。
 */
@SpringBootTest(properties = {
        "spring.cloud.nacos.discovery.enabled=false",
        "spring.cloud.discovery.enabled=false"
})
class FingerprintRegistrationOutboxRepositoryIT {

    @org.springframework.test.context.bean.override.convention.TestBean(name = "rocketMqDomainEventPublisher", methodName = "publicationTestPublisher")
    private com.codemind.review.domain.event.DomainEventPublisher publicationTestPublisher;

    static com.codemind.review.domain.event.DomainEventPublisher publicationTestPublisher() {
        return new com.codemind.review.support.RecordingDomainEventPublisher();
    }

    @Autowired
    private FingerprintRegistrationOutboxRepository outbox;

    @Autowired
    private ReviewTaskRepository tasks;

    @Autowired
    private JdbcClient jdbc;

    private final List<String> taskIds = new ArrayList<>();

    @AfterEach
    void cleanup() {
        for (String id : taskIds) {
            jdbc.sql("delete from fingerprint_registration_outbox where task_id=:id")
                    .param("id", UUID.fromString(id)).update();
            jdbc.sql("delete from review_task where task_id=:id")
                    .param("id", UUID.fromString(id)).update();
        }
    }

    @Test
    void insertIsIdempotentOnDuplicateTaskId() {
        ReviewTask task = task();

        outbox.insert(task.taskId(), task.repo(), task.prNumber(), List.of("fp1"));

        assertDoesNotThrow(() -> outbox.insert(task.taskId(), task.repo(), task.prNumber(), List.of("fp1")),
                "同一 taskId 重复插入（模拟 MQ 重复消费）不应抛异常中断主流程");
    }

    @Test
    void claimDueSkipsAcknowledgedEntries() {
        ReviewTask acked = task();
        ReviewTask pending = task();
        outbox.insert(acked.taskId(), acked.repo(), acked.prNumber(), List.of("fp1"));
        outbox.insert(pending.taskId(), pending.repo(), pending.prNumber(), List.of("fp2"));
        Instant now = Instant.now().plusSeconds(1);
        outbox.acknowledge(acked.taskId(), now);

        var batch = outbox.claimDue(now, 50, Duration.ofSeconds(120));

        assertTrue(batch.stream().anyMatch(d -> d.registration().taskId().equals(pending.taskId())));
        assertFalse(batch.stream().anyMatch(d -> d.registration().taskId().equals(acked.taskId())),
                "已确认的记录不该再被领取");
    }

    @Test
    void rescheduleSetsNextAttemptAndClearsLease() {
        ReviewTask task = task();
        outbox.insert(task.taskId(), task.repo(), task.prNumber(), List.of("fp1"));
        Instant now = Instant.now().plusSeconds(1);
        var delivery = outbox.claimDue(now, 50, Duration.ofSeconds(120)).stream()
                .filter(d -> d.registration().taskId().equals(task.taskId())).findFirst().orElseThrow();

        assertTrue(outbox.reschedule(task.taskId(), delivery.leaseToken(), now.plusSeconds(60), "boom"));
        assertTrue(outbox.claimDue(now.plusSeconds(1), 50, Duration.ofSeconds(120)).stream()
                .noneMatch(d -> d.registration().taskId().equals(task.taskId())),
                "next_attempt_at 未到不该被重新领取");
        var again = outbox.claimDue(now.plusSeconds(61), 50, Duration.ofSeconds(120)).stream()
                .filter(d -> d.registration().taskId().equals(task.taskId())).findFirst();
        assertTrue(again.isPresent(), "重排到期后应可再次领取");
    }

    private ReviewTask task() {
        ReviewTask task = ReviewTask.create("codemind/fingerprint-outbox-it-" + UUID.randomUUID(), 7, "head", "base",
                UUID.randomUUID().toString());
        assertTrue(tasks.insertIfAbsent(task));
        taskIds.add(task.taskId());
        return task;
    }
}
