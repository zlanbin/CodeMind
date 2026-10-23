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
        ReviewTask task = task(ReviewTaskStatus.AGGREGATING);
        task.assignReport(reportWithOneFinding());
        setup(task);
        when(tasks.markPublishedIfAggregating(task.taskId())).thenReturn(true);

        assertTrue(service().markPublished(ReviewPublished.of(task.taskId(), 1, false)));

        verify(lastReviewedHeads).upsert(task.repo(), task.prNumber(), task.baseSha(), task.headSha());
    }

    @Test void markPublishedInsertsFingerprintRegistrationWithReportFingerprints() {
        ReviewTask task = task(ReviewTaskStatus.AGGREGATING);
        task.assignReport(reportWithOneFinding());
        setup(task);
        when(tasks.markPublishedIfAggregating(task.taskId())).thenReturn(true);

        service().markPublished(ReviewPublished.of(task.taskId(), 1, false));

        String expectedFingerprint = task.report().inline().get(0).merged().get(0).fingerprint();
        verify(fingerprintOutbox).insert(task.taskId(), task.repo(), task.prNumber(), List.of(expectedFingerprint));
    }

    @Test void duplicateReceiptDoesNotDoubleInsertFingerprintRegistration() {
        ReviewTask task = task(ReviewTaskStatus.PUBLISHED);
        task.assignReport(reportWithOneFinding());
        setup(task);

        service().markPublished(ReviewPublished.of(task.taskId(), 1, false));

        verify(lastReviewedHeads, never()).upsert(any(), anyInt(), any(), any());
        verify(fingerprintOutbox, never()).insert(any(), any(), anyInt(), any());
    }

    private static com.codemind.review.domain.report.ReviewReport reportWithOneFinding() {
        var finding = com.codemind.review.domain.finding.Finding.of("A.java", 1,
                com.codemind.review.domain.finding.Severity.BLOCKER,
                com.codemind.review.domain.finding.Category.CORRECTNESS,
                "可能空指针", "加判空", "int x = 1;");
        var inline = new com.codemind.review.domain.report.InlineComment("A.java", 1,
                com.codemind.review.domain.finding.Severity.BLOCKER, List.of(finding));
        return new com.codemind.review.domain.report.ReviewReport(List.of(inline), List.of(), List.of(), 1);
    }

    void setup(ReviewTask task) {
        when(tasks.findByIdForUpdate(task.taskId())).thenReturn(Optional.of(task));
        var e=ReviewCompleted.of(task.taskId(),task.repo(),7,"head","summary",List.of());
        when(outbox.findByTaskId(task.taskId())).thenReturn(Optional.of(new OutboxEntry(e,e.occurredAt(),e.occurredAt(),0,null)));
    }
    static ReviewTask task(ReviewTaskStatus status) {
        return ReviewTask.rehydrate(UUID.randomUUID().toString(),"owner/repo",7,"head","base",UUID.randomUUID().toString(),status,java.time.Instant.now());
    }
}
