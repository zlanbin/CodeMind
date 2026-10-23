package com.codemind.integration.application;

import com.codemind.common.event.*;
import com.codemind.integration.application.port.*;
import com.codemind.integration.domain.publication.*;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class ReviewPublishServiceTest {
    final ReviewCompleted event = ReviewCompleted.of(UUID.randomUUID().toString(), "owner/repo", 7,
            "head", "summary", List.of(new ReviewCompleted.LineComment("A.java", 11, "issue")));
    final Ledger ledger = new Ledger();
    final Scm scm = new Scm();
    final Lease lease = new Lease();
    final List<ReviewPublished> receipts = new ArrayList<>();

    ReviewPublishService service() {
        return new ReviewPublishService(scm, id -> Optional.of(lease), ledger, e -> receipts.add((ReviewPublished)e));
    }

    @Test void durableBarrierPrecedesHttp() {
        scm.beforePost = () -> assertEquals(PublicationState.IN_FLIGHT, ledger.record.state());
        assertEquals(PublishOutcome.PUBLISHED, service().publish(event));
        assertEquals(1, scm.posts);
        assertEquals(1, receipts.getFirst().inlineCount());
        assertEquals(PublicationState.PUBLISHED, ledger.record.state());
        assertTrue(lease.closed);
    }
    @Test void databaseFailureBeforeAttemptMakesZeroPosts() {
        ledger.failBegin = true;
        assertThrows(IllegalStateException.class, () -> service().publish(event));
        assertEquals(0, scm.posts); assertTrue(receipts.isEmpty());
    }
    @Test void publishedRecordOnlyResendsReceipt() {
        service().publish(event);
        scm.failList = true;
        service().publish(event);
        assertEquals(1, scm.posts); assertEquals(2, receipts.size());
    }
    /**
     * S6 评审 I1：normal() 的实际渲染正文此前无任何用例检查过，变异测试证明把 marker()
     * 从 normal() 里删掉 21 个用例仍然全绿。本用例直接走闭环：INLINE 成功 POST 后，
     * scm 留住了那条 review（真实正文），对同 taskId 的新 ledger 实例重投必须靠 listReviews
     * 认出自己发过的 marker，不再 POST 第二次——这是 Review Focus 2 整条「重投只核查」
     * 路径的唯一依据，缺了它查重会永远找不到自己发的内容。
     */
    @Test void closedLoopPublishAndRedeliverFindsMarkerWithoutRepost() {
        assertEquals(PublishOutcome.PUBLISHED, service().publish(event));
        assertEquals(1, scm.posts);
        String postedBody = scm.last.body();
        assertTrue(postedBody.contains(event.summaryBody()), "normal() 必须保留原始总结正文");
        assertTrue(postedBody.contains(event.taskId()), "normal() 必须带本任务的幂等标记");

        // 模拟重启：新建一个没有任何记录的 ledger，但 GitHub 上那条 review（含 marker）还在。
        Ledger freshLedger = new Ledger();
        var service = new ReviewPublishService(scm, id -> Optional.of(lease), freshLedger,
                e -> receipts.add((ReviewPublished) e));
        assertEquals(PublishOutcome.PUBLISHED, service.publish(event));

        assertEquals(1, scm.posts, "重投查重命中标记后绝不能再 POST 第二次");
        assertEquals(42, freshLedger.record.result().reviewId(), "必须认回原来那条 review 的 id");
    }
    @Test void timeoutThenMissingMarkerNeverReposts() {
        scm.error = new ScmException("timeout", 0);
        assertEquals(PublishOutcome.AWAITING_CONFIRMATION, service().publish(event));
        assertEquals(PublicationState.UNCERTAIN, ledger.record.state());
        scm.error = null;
        assertEquals(PublishOutcome.AWAITING_CONFIRMATION, service().publish(event));
        assertEquals(1, scm.posts); assertTrue(receipts.isEmpty());
    }
    @Test void serverErrorNeverBlindlyRetries() {
        scm.error = new ScmException("server", 503);
        service().publish(event); service().publish(event);
        assertEquals(1, scm.posts); assertEquals(PublicationState.UNCERTAIN, ledger.record.state());
        assertTrue(receipts.isEmpty());
    }
    @Test void restartedInFlightOnlyReconciles() {
        ledger.register(event); ledger.beginAttempt(event.taskId(), "old", PublicationMode.INLINE);
        assertEquals(PublishOutcome.AWAITING_CONFIRMATION, service().publish(event));
        assertEquals(0, scm.posts); assertTrue(receipts.isEmpty());
    }
    @Test void markerFoundAfterTimeoutPersistsActualResult() {
        scm.error = new ScmException("timeout", 0); service().publish(event);
        scm.reviews.add(new ExistingReview(71, "summary\n<!-- codemind:task=" + event.taskId() + " -->"));
        assertEquals(PublishOutcome.PUBLISHED, service().publish(event));
        assertEquals(1, scm.posts); assertEquals(71, ledger.record.result().reviewId());
        assertEquals(1, receipts.getFirst().inlineCount());
    }
    @Test void receiptFailureDoesNotRepost() {
        var failing = new ReviewPublishService(scm, id -> Optional.of(lease), ledger,
                e -> { throw new IllegalStateException("MQ unavailable"); });
        assertThrows(IllegalStateException.class, () -> failing.publish(event));
        service().publish(event);
        assertEquals(1, scm.posts); assertEquals(PublicationState.PUBLISHED, ledger.record.state());
        assertEquals(1, receipts.size());
    }
    @Test void persistsSummaryOnlyBefore422Fallback() {
        scm.rejectFirst = true;
        scm.beforePost = () -> {
            assertEquals(PublicationState.IN_FLIGHT, ledger.record.state());
            if (scm.posts == 2) assertEquals(PublicationMode.SUMMARY_ONLY, ledger.record.mode());
        };
        assertEquals(PublishOutcome.PUBLISHED, service().publish(event));
        assertEquals(2, scm.posts); assertTrue(scm.last.comments().isEmpty());
        assertTrue(scm.last.body().contains("A.java:11"));
        assertTrue(receipts.getFirst().degraded()); assertEquals(0, receipts.getFirst().inlineCount());
    }
    @Test void restartAfter422UsesSummaryOnly() {
        ledger.register(event); ledger.beginAttempt(event.taskId(), "old", PublicationMode.INLINE);
        ledger.recordRejection(event.taskId(), "old", PublicationMode.SUMMARY_ONLY, "422");
        service().publish(event);
        assertEquals(1, scm.posts); assertTrue(scm.last.comments().isEmpty());
        assertTrue(receipts.getFirst().degraded());
    }
    @Test void summaryOnly422DoesNotLoop() {
        scm.error = new ScmException("rejected", 422);
        assertThrows(ScmException.class, () -> service().publish(event));
        assertEquals(2, scm.posts); assertEquals(PublicationState.READY, ledger.record.state());
        assertEquals(PublicationMode.SUMMARY_ONLY, ledger.record.mode()); assertTrue(receipts.isEmpty());
    }
    @Test void incompleteListingMakesZeroPosts() {
        scm.failList = true;
        assertThrows(ScmException.class, () -> service().publish(event));
        assertEquals(0, scm.posts); assertTrue(receipts.isEmpty());
    }
    @Test void lostLeaseBeforeHttpMakesZeroPosts() {
        lease.held = false;
        assertThrows(IllegalStateException.class, () -> service().publish(event));
        assertEquals(0, scm.posts); assertTrue(receipts.isEmpty());
    }
    @Test void differentPayloadRejected() {
        ledger.register(event);
        ReviewCompleted conflict = ReviewCompleted.of(event.taskId(), event.repo(), 7, "head", "different", event.comments());
        assertThrows(IllegalStateException.class, () -> service().publish(conflict));
        assertEquals(0, scm.posts);
    }
    @Test void exactMarkerMatchAvoidsSubstringCollision() {
        scm.reviews.add(new ExistingReview(8, "<!-- codemind:task=" + event.taskId() + "-other -->"));
        service().publish(event); assertEquals(1, scm.posts);
    }
    @Test void oldSenderCannotOverwriteConfirmedResult() {
        scm.beforePost = () -> ledger.reconcileSuccess(event.taskId(), new PublicationResult(90, 1, false));
        service().publish(event);
        assertEquals(90, ledger.record.result().reviewId());
        assertEquals(1, receipts.size());
    }

    @Test void twoConsumersWithExpiredLockStillMakeOneAttempt() throws Exception {
        java.util.concurrent.CountDownLatch posting = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        scm.beforePost = () -> {
            posting.countDown();
            try { assertTrue(release.await(5, java.util.concurrent.TimeUnit.SECONDS)); }
            catch (InterruptedException e) { throw new IllegalStateException(e); }
        };
        try (var pool = java.util.concurrent.Executors.newSingleThreadExecutor()) {
            var first = pool.submit(() -> service().publish(event));
            assertTrue(posting.await(5, java.util.concurrent.TimeUnit.SECONDS));
            assertEquals(PublishOutcome.AWAITING_CONFIRMATION, service().publish(event));
            release.countDown();
            assertEquals(PublishOutcome.PUBLISHED, first.get(5, java.util.concurrent.TimeUnit.SECONDS));
        }
        assertEquals(1, scm.posts); assertEquals(1, receipts.size());
    }

    @Test void lostLockAfterBarrierCannotPost() {
        ledger.afterBegin = () -> lease.held = false;
        assertThrows(IllegalStateException.class, () -> service().publish(event));
        assertEquals(0, scm.posts); assertEquals(PublicationState.IN_FLIGHT, ledger.record.state());
    }

    @Test void lockBusyMakesNoPublicationAttempt() {
        var service = new ReviewPublishService(scm, id -> Optional.empty(), ledger,
                e -> receipts.add((ReviewPublished)e));
        assertThrows(IllegalStateException.class, () -> service.publish(event));
        assertEquals(0, scm.posts); assertNull(ledger.record); assertTrue(receipts.isEmpty());
    }

    static final class Lease implements PublicationLease {
        boolean held = true, closed;
        public boolean isHeld() { return held; }
        public void close() { closed = true; }
    }
    static final class Scm implements ScmReviewPublisher {
        int posts; boolean failList, rejectFirst;
        ScmException error; ReviewDraft last; Runnable beforePost = () -> {};
        final List<ExistingReview> reviews = new ArrayList<>();
        public List<ExistingReview> listReviews(String repo, int pr) {
            if(failList) throw new ScmException("incomplete", 0);
            return List.copyOf(reviews);
        }
        public ExistingReview createReview(String repo, int pr, ReviewDraft draft) {
            posts++; last = draft; beforePost.run();
            if (rejectFirst && posts == 1) throw new ScmException("invalid line", 422);
            if(error != null) throw error;
            ExistingReview review = new ExistingReview(42, draft.body()); reviews.add(review); return review;
        }
    }
    static final class Ledger implements PublicationRepository {
        volatile PublicationRecord record; boolean failBegin;
        Runnable afterBegin = () -> {};
        public synchronized PublicationRecord register(ReviewCompleted event) {
            if(record == null) record = new PublicationRecord(event.taskId(), event, "digest", PublicationState.READY,
                    PublicationMode.INLINE, null, null, Instant.now(), Instant.now(), null);
            else if(!record.event().summaryBody().equals(event.summaryBody())) throw new IllegalStateException("conflict");
            return record;
        }
        public synchronized Optional<PublicationRecord> findByTaskId(String id) { return Optional.ofNullable(record); }
        public synchronized boolean beginAttempt(String id, String token, PublicationMode mode) {
            if(failBegin) throw new IllegalStateException("PG unavailable");
            if(record.state()!=PublicationState.READY || record.mode()!=mode) return false;
            set(PublicationState.IN_FLIGHT, mode, token, null); afterBegin.run(); return true;
        }
        public synchronized boolean markUncertain(String id, String token, String reason) {
            if(!matches(token)) return false; set(PublicationState.UNCERTAIN,record.mode(),token,null); return true;
        }
        public synchronized boolean recordRejection(String id, String token, PublicationMode next, String reason) {
            if(record.state()!=PublicationState.IN_FLIGHT||!matches(token)) return false;
            set(PublicationState.READY,next,null,null); return true;
        }
        public synchronized boolean recordSuccess(String id,String token,PublicationResult result) {
            if(!matches(token))return false; set(PublicationState.PUBLISHED,record.mode(),token,result);return true;
        }
        public synchronized boolean reconcileSuccess(String id,PublicationResult result) {
            if(record.state()==PublicationState.PUBLISHED)return false;
            set(PublicationState.PUBLISHED,record.mode(),record.attemptToken(),result);return true;
        }
        boolean matches(String token) { return record.state()!=PublicationState.PUBLISHED && Objects.equals(token,record.attemptToken()); }
        void set(PublicationState state,PublicationMode mode,String token,PublicationResult result) {
            record=new PublicationRecord(record.taskId(),record.event(),record.contentDigest(),state,mode,token,result,
                    record.createdAt(),Instant.now(),null);
        }
    }
}
