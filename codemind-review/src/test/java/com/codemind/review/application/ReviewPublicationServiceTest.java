package com.codemind.review.application;

import com.codemind.common.event.*;
import com.codemind.review.domain.publication.*;
import com.codemind.review.domain.task.*;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.*;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ReviewPublicationServiceTest {
    final ReviewTaskRepository tasks=mock(ReviewTaskRepository.class);
    final ReviewOutboxRepository outbox=mock(ReviewOutboxRepository.class);
    final com.codemind.review.domain.task.LastReviewedHeadRepository lastReviewedHeads =
            mock(com.codemind.review.domain.task.LastReviewedHeadRepository.class);
    final com.codemind.review.domain.publication.FingerprintRegistrationOutboxRepository fingerprintOutbox =
            mock(com.codemind.review.domain.publication.FingerprintRegistrationOutboxRepository.class);
    final TransactionTemplate tx=new TransactionTemplate(new AbstractPlatformTransactionManager(){
        protected Object doGetTransaction(){return new Object();}
        protected void doBegin(Object t,org.springframework.transaction.TransactionDefinition d){}
        protected void doCommit(DefaultTransactionStatus s){}
        protected void doRollback(DefaultTransactionStatus s){}
    });

    private ReviewPublicationService service() {
        return new ReviewPublicationService(tasks, outbox, tx, lastReviewedHeads, fingerprintOutbox);
    }

    @Test void receiptPublishesAndAcknowledgesOnlyCompletedTask() {
        ReviewTask task=task(ReviewTaskStatus.AGGREGATING);
        setup(task);
        when(tasks.markPublishedIfAggregating(task.taskId())).thenReturn(true);
        assertTrue(service().markPublished(ReviewPublished.of(task.taskId(),1,false)));
        verify(outbox).acknowledge(eq(task.taskId()),any());
    }
    @Test void repeatedReceiptIsIdempotent() {
        ReviewTask task=task(ReviewTaskStatus.PUBLISHED); setup(task);
        assertTrue(service().markPublished(ReviewPublished.of(task.taskId(),1,false)));
        verify(tasks,never()).markPublishedIfAggregating(any());
        verify(outbox).acknowledge(eq(task.taskId()),any());
    }
    @Test void unknownTaskAndMissingRequestAreNotAcknowledged() {
        when(tasks.findByIdForUpdate("missing")).thenReturn(Optional.empty());
        var service=service();
        assertFalse(service.markPublished(ReviewPublished.of("missing",0,false)));
        ReviewTask task=task(ReviewTaskStatus.AGGREGATING);
        when(tasks.findByIdForUpdate(task.taskId())).thenReturn(Optional.of(task));
        when(outbox.findByTaskId(task.taskId())).thenReturn(Optional.empty());
        assertFalse(service.markPublished(ReviewPublished.of(task.taskId(),0,false)));
        verify(outbox,never()).acknowledge(any(),any());
    }
    @Test void reviewingTaskCannotBePublishedByEarlyReceipt() {
        ReviewTask task=task(ReviewTaskStatus.REVIEWING); setup(task);
        assertFalse(service().markPublished(ReviewPublished.of(task.taskId(),0,false)));
        verify(outbox,never()).acknowledge(any(),any());
    }

    @Test void markPublishedUpsertsLastReviewedHeadOnFirstAdvance() {
        // task 不 assignReport：真实的 markPublished 调用方拿到的是 DB rehydrate 出来的
        // ReviewTask，report 永远是 null（M10 Final Review F1）。
        ReviewTask task = task(ReviewTaskStatus.AGGREGATING);
        setup(task, List.of("fp-1"));
        when(tasks.markPublishedIfAggregating(task.taskId())).thenReturn(true);

        assertTrue(service().markPublished(ReviewPublished.of(task.taskId(), 1, false)));

        verify(lastReviewedHeads).upsert(task.repo(), task.prNumber(), task.baseSha(), task.headSha());
    }

    @Test void markPublishedInsertsFingerprintRegistrationFromOutboxEventFingerprints() {
        ReviewTask task = task(ReviewTaskStatus.AGGREGATING);
        setup(task, List.of("fp-1", "fp-2"));
        when(tasks.markPublishedIfAggregating(task.taskId())).thenReturn(true);

        service().markPublished(ReviewPublished.of(task.taskId(), 1, false));

        verify(fingerprintOutbox).insert(task.taskId(), task.repo(), task.prNumber(), List.of("fp-1", "fp-2"));
    }

    @Test void duplicateReceiptDoesNotDoubleInsertFingerprintRegistration() {
        ReviewTask task = task(ReviewTaskStatus.PUBLISHED);
        setup(task, List.of("fp-1"));

        service().markPublished(ReviewPublished.of(task.taskId(), 1, false));

        verify(lastReviewedHeads, never()).upsert(any(), anyInt(), any(), any());
        verify(fingerprintOutbox, never()).insert(any(), any(), anyInt(), any());
    }

    void setup(ReviewTask task) {
        setup(task, List.of());
    }

    void setup(ReviewTask task, List<String> fingerprints) {
        when(tasks.findByIdForUpdate(task.taskId())).thenReturn(Optional.of(task));
        var e = ReviewCompleted.of(task.taskId(), task.repo(), 7, "head", "summary", List.of(), fingerprints);
        when(outbox.findByTaskId(task.taskId())).thenReturn(Optional.of(new OutboxEntry(e, e.occurredAt(), e.occurredAt(), 0, null)));
    }
    static ReviewTask task(ReviewTaskStatus status) {
        return ReviewTask.rehydrate(UUID.randomUUID().toString(),"owner/repo",7,"head","base",UUID.randomUUID().toString(),status,java.time.Instant.now());
    }
}
