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
    final TransactionTemplate tx=new TransactionTemplate(new AbstractPlatformTransactionManager(){
        protected Object doGetTransaction(){return new Object();}
        protected void doBegin(Object t,org.springframework.transaction.TransactionDefinition d){}
        protected void doCommit(DefaultTransactionStatus s){}
        protected void doRollback(DefaultTransactionStatus s){}
    });
    @Test void receiptPublishesAndAcknowledgesOnlyCompletedTask() {
        ReviewTask task=task(ReviewTaskStatus.AGGREGATING);
        setup(task);
        when(tasks.markPublishedIfAggregating(task.taskId())).thenReturn(true);
        assertTrue(new ReviewPublicationService(tasks,outbox,tx).markPublished(ReviewPublished.of(task.taskId(),1,false)));
        verify(outbox).acknowledge(eq(task.taskId()),any());
    }
    @Test void repeatedReceiptIsIdempotent() {
        ReviewTask task=task(ReviewTaskStatus.PUBLISHED); setup(task);
        assertTrue(new ReviewPublicationService(tasks,outbox,tx).markPublished(ReviewPublished.of(task.taskId(),1,false)));
        verify(tasks,never()).markPublishedIfAggregating(any());
        verify(outbox).acknowledge(eq(task.taskId()),any());
    }
    @Test void unknownTaskAndMissingRequestAreNotAcknowledged() {
        when(tasks.findByIdForUpdate("missing")).thenReturn(Optional.empty());
        var service=new ReviewPublicationService(tasks,outbox,tx);
        assertFalse(service.markPublished(ReviewPublished.of("missing",0,false)));
        ReviewTask task=task(ReviewTaskStatus.AGGREGATING);
        when(tasks.findByIdForUpdate(task.taskId())).thenReturn(Optional.of(task));
        when(outbox.findByTaskId(task.taskId())).thenReturn(Optional.empty());
        assertFalse(service.markPublished(ReviewPublished.of(task.taskId(),0,false)));
        verify(outbox,never()).acknowledge(any(),any());
    }
    @Test void reviewingTaskCannotBePublishedByEarlyReceipt() {
        ReviewTask task=task(ReviewTaskStatus.REVIEWING); setup(task);
        assertFalse(new ReviewPublicationService(tasks,outbox,tx).markPublished(ReviewPublished.of(task.taskId(),0,false)));
        verify(outbox,never()).acknowledge(any(),any());
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
