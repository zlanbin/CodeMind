package com.codemind.integration.application;

import com.codemind.common.event.FindingFeedbackReceived;
import com.codemind.common.event.FindingFeedbackReceived.FeedbackType;
import com.codemind.common.event.FindingFeedbackReceived.FindingRef;
import com.codemind.common.event.FindingMarker;
import com.codemind.integration.application.port.ScmException;
import com.codemind.integration.application.port.ScmFeedbackReader;
import com.codemind.integration.application.port.ScmFeedbackReader.ReviewCommentView;
import com.codemind.integration.domain.event.DomainEventPublisher;
import com.codemind.integration.domain.feedback.FindingFeedbackRepository;
import com.codemind.integration.domain.feedback.FindingFeedbackRepository.PublishedReview;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 反馈收集（M9 D1/D6/D8）：轮询已发布 review 的行级评论，把 👎 与对 CodeMind 评论的回复登记进账本，
 * 再把账本里 PENDING 的事件发 MQ。登记与投递分离：发 MQ 失败不丢反馈，下一轮重投同一 eventId。
 *
 * <p>「CodeMind 的评论」= 属于账本里已发布 review 且带 Finding 标记。不按作者过滤：发布账号自己 👎 也算反馈。
 * 回复本身带标记的不算反馈（不会是人写的）。
 */
@Service
public class FeedbackCollectionService {

    private static final Logger log = LoggerFactory.getLogger(FeedbackCollectionService.class);
    private static final int DISPATCH_BATCH = 50;

    private final ScmFeedbackReader reader;
    private final FindingFeedbackRepository repository;
    private final DomainEventPublisher publisher;
    private final Clock clock;
    private final java.util.Set<String> trustedLogins;

    /** @param trustedLogins 不查权限直接信任的账号（如团队里没有写权限的评审者）；可为空 */
    public FeedbackCollectionService(ScmFeedbackReader reader, FindingFeedbackRepository repository,
                                     DomainEventPublisher publisher, Clock clock,
                                     @org.springframework.beans.factory.annotation.Value(
                                             "${codemind.feedback.trusted-logins:}") java.util.Set<String> trustedLogins) {
        this.reader = reader;
        this.repository = repository;
        this.publisher = publisher;
        this.clock = clock;
        this.trustedLogins = java.util.Set.copyOf(trustedLogins);
    }

    /** 轮询 lookback 内所有已发布 review；单个 PR 读 GitHub 失败只跳过它。返回新登记条数。 */
    public int collectRecent(Duration lookback) {
        int registered = 0;
        for (PublishedReview review : repository.findPublishedSince(clock.instant().minus(lookback))) {
            try {
                registered += collect(review);
            } catch (ScmException e) {
                log.warn("读取反馈失败，跳过 repo={} pr={} status={}", review.repo(), review.prNumber(), e.statusCode());
            }
        }
        return registered;
    }

    /** 手动触发：只轮询指定 PR 的已发布 review。 */
    public int collect(String repo, int prNumber, Duration lookback) {
        int registered = 0;
        for (PublishedReview review : repository.findPublishedSince(clock.instant().minus(lookback))) {
            if (review.repo().equals(repo) && review.prNumber() == prNumber) {
                registered += collect(review);
            }
        }
        return registered;
    }

    int collect(PublishedReview review) {
        List<ReviewCommentView> comments = reader.listReviewComments(review.repo(), review.prNumber());
        Map<Long, ReviewCommentView> ours = new HashMap<>();
        for (ReviewCommentView c : comments) {
            if (c.reviewId() != null && c.reviewId() == review.reviewId() && !FindingMarker.parseAll(c.body()).isEmpty()) {
                ours.put(c.id(), c);
            }
        }
        int registered = 0;
        Map<String, Boolean> trust = new HashMap<>();
        // 先回复后 👎：同一人对同一条评论既回复又 👎 时只学回复（M9 D15）。回复说清了否决哪条、为什么，
        // 👎 只是「不要」——两者都学时，概括不准的 👎 会把回复学到的规则当成矛盾删掉（T6 实测）
        java.util.Set<String> repliedBy = new java.util.HashSet<>();
        for (ReviewCommentView reply : comments) {
            ReviewCommentView target = reply.inReplyToId() == null ? null : ours.get(reply.inReplyToId());
            if (target != null && FindingMarker.parseAll(reply.body()).isEmpty() && !reply.body().isBlank()
                    && trusted(review.repo(), reply.authorLogin(), trust)) {
                repliedBy.add(target.id() + "|" + reply.authorLogin());
                registered += register(review, target, FeedbackType.REPLY, reply.id(), reply.authorLogin(),
                        reply.body(), false);
            }
        }
        for (ReviewCommentView comment : ours.values()) {
            for (var reaction : reader.listThumbsDown(review.repo(), comment.id())) {
                if (trusted(review.repo(), reaction.authorLogin(), trust)) {
                    boolean superseded = repliedBy.contains(comment.id() + "|" + reaction.authorLogin());
                    registered += register(review, comment, FeedbackType.THUMBS_DOWN, reaction.id(),
                            reaction.authorLogin(), null, superseded);
                }
            }
        }
        if (registered > 0) {
            log.info("登记反馈 repo={} pr={} 新增={}", review.repo(), review.prNumber(), registered);
        }
        return registered;
    }

    /**
     * 只学有写权限的人的反馈（S6 标准轴）：公开仓库里任何人都能 👎，不设门槛等于让外人压掉真缺陷。
     * 白名单优先；查不到权限（403 等）按不可信处理并记日志，不抛出——一个人查不到不该拖垮整个 PR。
     */
    private boolean trusted(String repo, String login, Map<String, Boolean> cache) {
        if (login == null || login.isBlank()) {
            return false;
        }
        if (trustedLogins.contains(login)) {
            return true;
        }
        return cache.computeIfAbsent(login, l -> {
            try {
                return reader.canWrite(repo, l);
            } catch (ScmException e) {
                log.warn("查不到反馈人权限，按不可信处理 repo={} login={} status={}", repo, l, e.statusCode());
                return false;
            }
        });
    }

    /** 把 PENDING 的反馈发 MQ，返回成功条数。 */
    public int dispatchPending() {
        int sent = 0;
        for (FindingFeedbackReceived event : repository.findPending(DISPATCH_BATCH)) {
            try {
                publisher.publish(event);
                if (repository.markSent(event.eventId(), clock.instant())) {
                    sent++;
                }
            } catch (RuntimeException e) {
                log.warn("反馈投递失败，下轮重投 eventId={} 原因={}", event.eventId(), e.getClass().getSimpleName());
                repository.recordFailure(event.eventId(), e.getClass().getSimpleName() + ": " + e.getMessage());
            }
        }
        return sent;
    }

    /** @return 新登记且待投递的条数；被回复取代的 👎 只占位去重、不计数 */
    private int register(PublishedReview review, ReviewCommentView comment, FeedbackType type, long sourceId,
                         String author, String replyText, boolean superseded) {
        List<FindingRef> findings = FindingMarker.parseAll(comment.body());
        FindingFeedbackReceived event = FindingFeedbackReceived.of(review.repo(), review.prNumber(), comment.id(),
                type, sourceId, author, replyText, FindingMarker.strip(comment.body()).strip(), findings);
        boolean added = repository.registerIfAbsent(event, superseded);
        return added && !superseded ? 1 : 0;
    }
}
