package com.codemind.integration.application;

import com.codemind.common.event.ReviewCompleted;
import com.codemind.common.event.ReviewPublished;
import com.codemind.integration.application.port.*;
import com.codemind.integration.domain.event.DomainEventPublisher;
import com.codemind.integration.domain.publication.*;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/** 发前账本栅栏优先于锁；不确定的 POST 只能核查，不能自动重发。 */
@Service
public class ReviewPublishService {
    private static final Set<Integer> DEFINITE_REJECTIONS = Set.of(400, 401, 403, 404, 405, 406, 410, 415, 422, 429);
    private final ScmReviewPublisher scm;
    private final PublicationLock locks;
    private final PublicationRepository repository;
    private final DomainEventPublisher events;

    public ReviewPublishService(ScmReviewPublisher scm, PublicationLock locks,
                                PublicationRepository repository, DomainEventPublisher events) {
        this.scm = scm;
        this.locks = locks;
        this.repository = repository;
        this.events = events;
    }

    public PublishOutcome publish(ReviewCompleted request) {
        try (PublicationLease lease = locks.tryAcquire(request.taskId())
                .orElseThrow(() -> new IllegalStateException("发布锁被占用: " + request.taskId()))) {
            PublicationRecord record = repository.register(request);
            if (record.state() == PublicationState.PUBLISHED) return receipt(record);
            ReviewCompleted event = record.event();
            var existing = scm.listReviews(event.repo(), event.prNumber()).stream()
                    .filter(review -> ReviewPublicationBodies.matches(review.body(), event.taskId())).findFirst();
            if (existing.isPresent()) {
                repository.reconcileSuccess(event.taskId(), result(record, existing.get().id()));
                return receipt(current(event.taskId()));
            }
            if (record.state() != PublicationState.READY) return PublishOutcome.AWAITING_CONFIRMATION;
            return attempt(record, lease);
        }
    }

    private PublishOutcome attempt(PublicationRecord record, PublicationLease lease) {
        ReviewCompleted event = record.event();
        requireLease(lease);
        String token = UUID.randomUUID().toString();
        if (!repository.beginAttempt(event.taskId(), token, record.mode())) {
            PublicationRecord current = current(event.taskId());
            return current.state() == PublicationState.PUBLISHED ? receipt(current) : PublishOutcome.AWAITING_CONFIRMATION;
        }
        // 即使在此失锁未发出请求，也保留 IN_FLIGHT，由核查/人工处理，而不是猜测未写入。
        requireLease(lease);
        ExistingReview created;
        try {
            boolean summaryOnly = record.mode() == PublicationMode.SUMMARY_ONLY;
            List<DraftComment> comments = summaryOnly ? List.of() : event.comments().stream()
                    .map(c -> new DraftComment(c.path(), c.line(), c.body())).toList();
            created = scm.createReview(event.repo(), event.prNumber(), new ReviewDraft(event.headSha(),
                    summaryOnly ? ReviewPublicationBodies.summaryOnly(event) : ReviewPublicationBodies.normal(event), comments));
        } catch (ScmException e) {
            if (!DEFINITE_REJECTIONS.contains(e.statusCode())) {
                repository.markUncertain(event.taskId(), token, "SCM result uncertain: " + e.statusCode());
                return PublishOutcome.AWAITING_CONFIRMATION;
            }
            boolean fallback = e.statusCode() == 422 && record.mode() == PublicationMode.INLINE && !event.comments().isEmpty();
            PublicationMode nextMode = fallback ? PublicationMode.SUMMARY_ONLY : record.mode();
            if (!repository.recordRejection(event.taskId(), token, nextMode, "SCM rejected: " + e.statusCode())) {
                PublicationRecord current = current(event.taskId());
                return current.state() == PublicationState.PUBLISHED ? receipt(current) : PublishOutcome.AWAITING_CONFIRMATION;
            }
            if (fallback) return attempt(current(event.taskId()), lease);
            throw e;
        }
        repository.recordSuccess(event.taskId(), token, result(record, created.id()));
        return receipt(current(event.taskId()));
    }

    private PublicationResult result(PublicationRecord record, long reviewId) {
        boolean degraded = record.mode() == PublicationMode.SUMMARY_ONLY;
        return new PublicationResult(reviewId, degraded ? 0 : record.event().comments().size(), degraded);
    }

    private PublishOutcome receipt(PublicationRecord record) {
        if (record.state() != PublicationState.PUBLISHED || record.result() == null) return PublishOutcome.AWAITING_CONFIRMATION;
        events.publish(ReviewPublished.of(record.taskId(), record.result().inlineCount(), record.result().degraded()));
        return PublishOutcome.PUBLISHED;
    }

    private PublicationRecord current(String taskId) {
        return repository.findByTaskId(taskId).orElseThrow(() -> new IllegalStateException("发布记录不存在: " + taskId));
    }
    private static void requireLease(PublicationLease lease) {
        if (!lease.isHeld()) throw new IllegalStateException("发布租约已失效");
    }
}
