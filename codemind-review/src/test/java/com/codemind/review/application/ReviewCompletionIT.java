package com.codemind.review.application;

import com.codemind.common.event.ReviewCompleted;
import com.codemind.review.domain.publication.ReviewOutboxRepository;
import com.codemind.review.domain.task.*;
import com.codemind.review.infrastructure.persistence.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest(classes = ReviewCompletionIT.Config.class, properties = {
        "spring.cloud.nacos.discovery.enabled=false", "spring.cloud.discovery.enabled=false",
        "spring.autoconfigure.exclude=org.apache.rocketmq.spring.autoconfigure.RocketMQAutoConfiguration,org.springframework.ai.model.openai.autoconfigure.OpenAiChatAutoConfiguration,org.springframework.ai.model.openai.autoconfigure.OpenAiEmbeddingAutoConfiguration"
})
class ReviewCompletionIT {
    @Configuration
    @EnableAutoConfiguration
    @Import({JdbcReviewTaskRepository.class, JdbcReviewOutboxRepository.class, ReviewCompletionService.class})
    static class Config { }

    @Autowired ReviewCompletionService completion;
    @Autowired ReviewOutboxRepository outbox;
    @Autowired JdbcClient jdbc;
    @MockitoSpyBean ReviewTaskRepository tasks;
    private final List<String> ids = new ArrayList<>();

    @AfterEach
    void cleanup() {
        reset(tasks);
        for (String id : ids) {
            jdbc.sql("delete from review_publication_outbox where task_id=:id").param("id", UUID.fromString(id)).update();
            jdbc.sql("delete from review_task where task_id=:id").param("id", UUID.fromString(id)).update();
        }
    }

    @Test
    void failureAfterInsertRollsBackEntireHandoff() {
        ReviewTask task = task();
        doThrow(new IllegalStateException("refresh failed")).when(tasks)
                .updateStatus(task.taskId(), ReviewTaskStatus.AGGREGATING);
        assertThrows(IllegalStateException.class, () -> completion.register(event(task)));
        assertTrue(outbox.findByTaskId(task.taskId()).isEmpty());
        assertEquals(ReviewTaskStatus.AGGREGATING, tasks.findById(task.taskId()).orElseThrow().status());
    }

    @Test
    void committedHandoffUsesOriginalEventAndProtectsStatus() {
        ReviewTask task = task();
        ReviewCompleted first = event(task);
        assertEquals(first, completion.register(first));
        assertEquals(first, completion.register(event(task)));
        assertFalse(tasks.markFailedIfUnpublished(task.taskId()));
    }

    private ReviewTask task() {
        ReviewTask task = ReviewTask.create("codemind/completion-it-" + UUID.randomUUID(), 7, "head", "base", UUID.randomUUID().toString());
        assertTrue(tasks.insertIfAbsent(task));
        ids.add(task.taskId());
        tasks.updateStatus(task.taskId(), ReviewTaskStatus.AGGREGATING);
        return task;
    }
    private ReviewCompleted event(ReviewTask task) {
        return ReviewCompleted.of(task.taskId(), task.repo(), task.prNumber(), task.headSha(), "summary", List.of());
    }
}
