package com.codemind.review.application;

import com.codemind.common.event.ReviewPublished;
import com.codemind.review.domain.publication.FingerprintRegistrationOutboxRepository;
import com.codemind.review.domain.publication.ReviewOutboxRepository;
import com.codemind.review.domain.task.LastReviewedHeadRepository;
import com.codemind.review.domain.task.ReviewTask;
import com.codemind.review.domain.task.ReviewTaskRepository;
import com.codemind.review.domain.task.ReviewTaskStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;

/** 成功状态与 outbox 确认同事务，统一先锁 task。 */
@Service
public class ReviewPublicationService {
    private static final Logger log = LoggerFactory.getLogger(ReviewPublicationService.class);

    private final ReviewTaskRepository tasks;
    private final ReviewOutboxRepository outbox;
    private final TransactionTemplate transactions;
    private final LastReviewedHeadRepository lastReviewedHeadRepository;
    private final FingerprintRegistrationOutboxRepository fingerprintOutbox;

    public ReviewPublicationService(ReviewTaskRepository tasks, ReviewOutboxRepository outbox,
                                    TransactionTemplate transactions,
                                    LastReviewedHeadRepository lastReviewedHeadRepository,
                                    FingerprintRegistrationOutboxRepository fingerprintOutbox) {
        this.tasks = tasks;
        this.outbox = outbox;
        this.transactions = transactions;
        this.lastReviewedHeadRepository = lastReviewedHeadRepository;
        this.fingerprintOutbox = fingerprintOutbox;
    }

    public boolean markPublished(ReviewPublished event) {
        return Boolean.TRUE.equals(transactions.execute(status -> {
            var task = tasks.findByIdForUpdate(event.taskId());
            var outboxEntry = outbox.findByTaskId(event.taskId());
            if (task.isEmpty() || outboxEntry.isEmpty()) {
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

            // 只在「首次把任务推进到 PUBLISHED」时落 M10 的基准与指纹账本，重复回执（任务早已
            // PUBLISHED）不该重复写——否则 last_reviewed_head 会被旧回执的 headSha 覆盖掉新基准。
            boolean firstAdvance = !alreadyPublished && advanced;
            if (firstAdvance) {
                ReviewTask t = task.get();
                lastReviewedHeadRepository.upsert(t.repo(), t.prNumber(), t.baseSha(), t.headSha());
                // 指纹只能从 outbox 存的 ReviewCompleted 事件里拿：这里的 task 是从数据库
                // rehydrate 出来的，report 字段从未持久化，永远是 null（M10 Final Review F1）。
                List<String> fingerprints = outboxEntry.get().event().fingerprints();
                if (!fingerprints.isEmpty()) {
                    fingerprintOutbox.insert(t.taskId(), t.repo(), t.prNumber(), fingerprints);
                }
            }
            return true;
        }));
    }
}
