package com.codemind.review.application;

import com.codemind.common.event.ReviewCompleted;
import com.codemind.review.domain.task.*;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ReviewCompletionServiceTest {
    private final ReviewTaskRepository tasks = mock(ReviewTaskRepository.class);
    private final ReviewOutboxDispatcherTest.MemoryOutbox outbox = new ReviewOutboxDispatcherTest.MemoryOutbox();

    @Test
    void completionReusesFirstPayload() {
        ReviewTask task = aggregating();
        when(tasks.findByIdForUpdate(task.taskId())).thenReturn(Optional.of(task));
        var service = service();
        ReviewCompleted first = ReviewCompleted.of(task.taskId(), task.repo(), task.prNumber(),
                task.headSha(), "first summary", java.util.List.of());
        assertEquals(first, service.register(first));
        ReviewCompleted changed = ReviewCompleted.of(task.taskId(), task.repo(), task.prNumber(),
                task.headSha(), "new summary", java.util.List.of());
        assertEquals(first, service.register(changed));
        assertEquals(1, outbox.events.size());
    }

    @Test
    void rejectsIncompleteTaskAndDoesNotInsertRequest() {
        ReviewTask task = ReviewTask.create("owner/repo", 7, "head", "base", UUID.randomUUID().toString());
        when(tasks.findByIdForUpdate(task.taskId())).thenReturn(Optional.of(task));
        assertThrows(IllegalStateException.class, () -> service().register(ReviewCompleted.of(
                task.taskId(), task.repo(), task.prNumber(), task.headSha(), "body", java.util.List.of())));
        assertTrue(outbox.events.isEmpty());
    }

    private ReviewCompletionService service() {
        return new ReviewCompletionService(tasks, outbox, new org.springframework.transaction.support.TransactionTemplate(
                new org.springframework.transaction.support.AbstractPlatformTransactionManager() {
                    protected Object doGetTransaction() { return new Object(); }
                    protected void doBegin(Object transaction, org.springframework.transaction.TransactionDefinition definition) { }
                    protected void doCommit(org.springframework.transaction.support.DefaultTransactionStatus status) { }
                    protected void doRollback(org.springframework.transaction.support.DefaultTransactionStatus status) { }
                }));
    }

    private static ReviewTask aggregating() {
        ReviewTask task = ReviewTask.create("owner/repo", 7, "head", "base", UUID.randomUUID().toString());
        task.transitionTo(ReviewTaskStatus.PLANNING);
        task.transitionTo(ReviewTaskStatus.REVIEWING);
        task.transitionTo(ReviewTaskStatus.AGGREGATING);
        return task;
    }
}
