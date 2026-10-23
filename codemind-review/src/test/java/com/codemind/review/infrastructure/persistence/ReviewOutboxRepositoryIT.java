package com.codemind.review.infrastructure.persistence;

import com.codemind.common.event.ReviewCompleted;
import com.codemind.review.application.ReviewCompletionService;
import com.codemind.review.domain.publication.ReviewOutboxRepository;
import com.codemind.review.domain.task.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.*;
import java.util.*;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(classes = ReviewOutboxRepositoryIT.Config.class, properties = {
        "spring.cloud.nacos.discovery.enabled=false", "spring.cloud.discovery.enabled=false",
        "spring.autoconfigure.exclude=org.apache.rocketmq.spring.autoconfigure.RocketMQAutoConfiguration,org.springframework.ai.model.openai.autoconfigure.OpenAiChatAutoConfiguration,org.springframework.ai.model.openai.autoconfigure.OpenAiEmbeddingAutoConfiguration"
})
class ReviewOutboxRepositoryIT {
    @Configuration
    @EnableAutoConfiguration
    @Import({JdbcReviewTaskRepository.class, JdbcReviewOutboxRepository.class, ReviewCompletionService.class})
    static class Config { }

    @Autowired ReviewTaskRepository tasks;
    @Autowired ReviewOutboxRepository outbox;
    @Autowired ReviewCompletionService completion;
    @Autowired JdbcClient jdbc;
    @Autowired TransactionTemplate transactions;
    private final List<String> ids = new ArrayList<>();

    @AfterEach
    void cleanup() {
        for (String id : ids) {
            jdbc.sql("delete from review_publication_outbox where task_id=:id").param("id", UUID.fromString(id)).update();
            jdbc.sql("delete from review_task where task_id=:id").param("id", UUID.fromString(id)).update();
        }
    }

    @Test
    void roundTripsOriginalEventAndOnlyOneWorkerClaims() throws Exception {
        ReviewCompleted event = register();
        assertEquals(event, outbox.findByTaskId(event.taskId()).orElseThrow().event());
        Instant now = Instant.now().plusSeconds(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            CyclicBarrier barrier = new CyclicBarrier(2);
            Callable<Boolean> claim = () -> { barrier.await(); return outbox.claimTask(event.taskId(), now, Duration.ofSeconds(120)).isPresent(); };
            var a = pool.submit(claim);
            var b = pool.submit(claim);
            assertNotEquals(a.get(10, TimeUnit.SECONDS), b.get(10, TimeUnit.SECONDS));
        }
        assertEquals(1, outbox.findByTaskId(event.taskId()).orElseThrow().attempts());
    }

    @Test
    void expiredLeaseCanBeReclaimedAndOldTokenCannotReschedule() {
        ReviewCompleted event = register();
        Instant now = Instant.now().plusSeconds(1);
        var first = outbox.claimTask(event.taskId(), now, Duration.ofSeconds(120)).orElseThrow();
        assertTrue(outbox.claimTask(event.taskId(), now.plusSeconds(119), Duration.ofSeconds(120)).isEmpty());
        var second = outbox.claimTask(event.taskId(), now.plusSeconds(121), Duration.ofSeconds(120)).orElseThrow();
        assertNotEquals(first.leaseToken(), second.leaseToken());
        assertFalse(outbox.reschedule(event.taskId(), first.leaseToken(), now.plusSeconds(200), "late"));
        assertTrue(outbox.reschedule(event.taskId(), second.leaseToken(), now.plusSeconds(200), null));
    }

    @Test
    void acknowledgedRequestCannotBeReopened() {
        ReviewCompleted event = register();
        Instant now = Instant.now().plusSeconds(1);
        var delivery = outbox.claimTask(event.taskId(), now, Duration.ofSeconds(120)).orElseThrow();
        outbox.acknowledge(event.taskId(), now);
        assertFalse(outbox.reschedule(event.taskId(), delivery.leaseToken(), now, "late"));
        assertTrue(outbox.claimTask(event.taskId(), now.plusSeconds(1000), Duration.ofSeconds(120)).isEmpty());
    }

    @Test
    void registrationRollbackLeavesNoOutboxAndConditionalFailureProtectsCompletedRequest() {
        ReviewTask task = task();
        ReviewCompleted event = event(task);
        assertThrows(IllegalStateException.class, () -> transactions.executeWithoutResult(status -> {
            completion.register(event);
            throw new IllegalStateException("rollback");
        }));
        assertTrue(outbox.findByTaskId(task.taskId()).isEmpty());
        assertEquals(event, completion.register(event));
        assertFalse(tasks.markFailedIfUnpublished(task.taskId()));
        assertEquals(ReviewTaskStatus.AGGREGATING, tasks.findById(task.taskId()).orElseThrow().status());
    }

    @Test
    void batchClaimsOnlyDueUnacknowledgedRequestsAndSameEventIsReused() {
        ReviewCompleted first = register();
        ReviewCompleted second = register();
        Instant now = Instant.now().plusSeconds(1);
        var claim = outbox.claimTask(first.taskId(), now, Duration.ofSeconds(120)).orElseThrow();
        outbox.reschedule(first.taskId(), claim.leaseToken(), now.plusSeconds(60), null);
        var batch = outbox.claimDue(now, 50, Duration.ofSeconds(120));
        assertTrue(batch.stream().anyMatch(d -> d.event().equals(second)));
        assertFalse(batch.stream().anyMatch(d -> d.event().equals(first)));
        ReviewCompleted changed = ReviewCompleted.of(first.taskId(), first.repo(), first.prNumber(), first.headSha(), "replacement", List.of());
        assertEquals(first, completion.register(changed));
    }

    private ReviewCompleted register() { return completion.register(event(task())); }
    private ReviewTask task() {
        ReviewTask task = ReviewTask.create("codemind/outbox-it-" + UUID.randomUUID(), 7, "head", "base", UUID.randomUUID().toString());
        assertTrue(tasks.insertIfAbsent(task));
        ids.add(task.taskId());
        tasks.updateStatus(task.taskId(), ReviewTaskStatus.AGGREGATING);
        return task;
    }
    private ReviewCompleted event(ReviewTask task) {
        return ReviewCompleted.of(task.taskId(), task.repo(), task.prNumber(), task.headSha(), "summary",
                List.of(new ReviewCompleted.LineComment("A.java", 1, "issue")));
    }
}
