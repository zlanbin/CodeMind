package com.codemind.review.application;

import com.codemind.common.event.ReviewPublished;
import com.codemind.review.domain.publication.ReviewOutboxRepository;
import com.codemind.review.domain.task.ReviewTaskRepository;
import com.codemind.review.domain.task.ReviewTaskStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;

/** 成功状态与 outbox 确认同事务，统一先锁 task。 */
@Service
public class ReviewPublicationService {
    private static final Logger log = LoggerFactory.getLogger(ReviewPublicationService.class);

    private final ReviewTaskRepository tasks;
    private final ReviewOutboxRepository outbox;
    private final TransactionTemplate transactions;

    public ReviewPublicationService(ReviewTaskRepository tasks, ReviewOutboxRepository outbox,
                                    TransactionTemplate transactions) {
        this.tasks = tasks;
        this.outbox = outbox;
        this.transactions = transactions;
    }

    public boolean markPublished(ReviewPublished event) {
        return Boolean.TRUE.equals(transactions.execute(status -> {
            var task = tasks.findByIdForUpdate(event.taskId());
            if (task.isEmpty() || outbox.findByTaskId(event.taskId()).isEmpty()) {
                log.warn("发布回执缺少任务或原请求 taskId={}", event.taskId());
                return false;
            }
            boolean alreadyPublished = task.get().status() == ReviewTaskStatus.PUBLISHED;
            boolean advanced = alreadyPublished
                    || (task.get().status() == ReviewTaskStatus.AGGREGATING
                        && tasks.markPublishedIfAggregating(event.taskId()));
            if (!advanced) {
                log.warn("发布回执状态不匹配 taskId={} status={}", event.taskId(), task.get().status());
                return false;
            }
            outbox.acknowledge(event.taskId(), Instant.now());
            return true;
        }));
    }
}
