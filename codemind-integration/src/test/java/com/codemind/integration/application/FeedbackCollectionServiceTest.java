package com.codemind.integration.application;

import com.codemind.common.event.DomainEvent;
import com.codemind.common.event.FindingFeedbackReceived;
import com.codemind.common.event.FindingFeedbackReceived.FeedbackType;
import com.codemind.common.event.FindingFeedbackReceived.FindingRef;
import com.codemind.common.event.FindingMarker;
import com.codemind.integration.application.port.ScmException;
import com.codemind.integration.application.port.ScmFeedbackReader;
import com.codemind.integration.domain.feedback.FindingFeedbackRepository;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class FeedbackCollectionServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-23T00:00:00Z");
    private static final String FP1 = "1".repeat(64);
    private static final String FP2 = "2".repeat(64);
    private static final long REVIEW = 555;

    private final StubReader reader = new StubReader();
    private final InMemoryLedger ledger = new InMemoryLedger();
    private final List<DomainEvent> published = new ArrayList<>();
    private boolean publishFails;
    private final FeedbackCollectionService service = new FeedbackCollectionService(reader, ledger, event -> {
        if (publishFails) {
            throw new IllegalStateException("broker down");
        }
        published.add(event);
    }, Clock.fixed(NOW, ZoneOffset.UTC), java.util.Set.of("whitelisted"));

    private final String ourBody = "### MAJOR\n\n描述：越界\n\n" + FindingMarker.format(FP1, "CORRECTNESS")
            + "\n\n描述：缺测试\n\n" + FindingMarker.format(FP2, "TEST");

    @Test
    void thumbsDownOnOurCommentBecomesFeedbackWithAllMergedFindings() {
        ledger.published.add(new FindingFeedbackRepository.PublishedReview("o/r", 2, REVIEW));
        reader.comments.add(comment(10, REVIEW, null, "codemind-bot", ourBody));
        reader.reactions.put(10L, List.of(new ScmFeedbackReader.ReactionView(900, "lan-bin")));

        assertThat(service.collectRecent(Duration.ofDays(14))).isEqualTo(1);

        FindingFeedbackReceived event = ledger.pending().get(0);
        assertThat(event.feedbackType()).isEqualTo(FeedbackType.THUMBS_DOWN);
        assertThat(event.commentId()).isEqualTo(10);
        assertThat(event.sourceId()).isEqualTo(900);
        assertThat(event.author()).isEqualTo("lan-bin");
        assertThat(event.findings()).containsExactly(new FindingRef(FP1, "CORRECTNESS"), new FindingRef(FP2, "TEST"));
        assertThat(event.commentBody()).contains("越界", "缺测试").doesNotContain("codemind:finding");
        assertThat(ledger.lastSince).isEqualTo(NOW.minus(Duration.ofDays(14)));
    }

    @Test
    void humanReplyToOurCommentIsForwardedOtherCommentsAreIgnored() {
        ledger.published.add(new FindingFeedbackRepository.PublishedReview("o/r", 2, REVIEW));
        reader.comments.add(comment(10, REVIEW, null, "codemind-bot", ourBody));
        reader.comments.add(comment(11, 600L, 10L, "lan-bin", "不适用，因为这里是 DTO"));
        // 别的 review 里带标记的评论（旧任务或别人伪造）不算我们的
        reader.comments.add(comment(20, 999L, null, "codemind-bot", ourBody));
        reader.comments.add(comment(21, 601L, 20L, "lan-bin", "回复别的 review"));
        // 我们 review 里没有标记的评论（M9 前发布的）收不到反馈
        reader.comments.add(comment(30, REVIEW, null, "codemind-bot", "旧格式意见"));
        reader.comments.add(comment(31, 602L, 30L, "lan-bin", "回复旧格式"));
        // 空回复、带标记的回复都不算
        reader.comments.add(comment(12, 603L, 10L, "lan-bin", "  "));
        reader.comments.add(comment(13, 604L, 10L, "codemind-bot", FindingMarker.format(FP1, "STYLE")));

        assertThat(service.collectRecent(Duration.ofDays(14))).isEqualTo(1);

        FindingFeedbackReceived event = ledger.pending().get(0);
        assertThat(event.feedbackType()).isEqualTo(FeedbackType.REPLY);
        assertThat(event.sourceId()).isEqualTo(11);
        assertThat(event.commentId()).isEqualTo(10);
        assertThat(event.replyText()).isEqualTo("不适用，因为这里是 DTO");
        assertThat(reader.reactionCalls).containsExactly(10L);
    }

    @Test
    void repollingRegistersNothingNew() {
        ledger.published.add(new FindingFeedbackRepository.PublishedReview("o/r", 2, REVIEW));
        reader.comments.add(comment(10, REVIEW, null, "codemind-bot", ourBody));
        reader.reactions.put(10L, List.of(new ScmFeedbackReader.ReactionView(900, "lan-bin")));

        service.collectRecent(Duration.ofDays(14));
        assertThat(service.collectRecent(Duration.ofDays(14))).isZero();
        assertThat(ledger.events).hasSize(1);
    }

    @Test
    void onlyWritersOrWhitelistedLoginsAreLearnedFrom() {
        ledger.published.add(new FindingFeedbackRepository.PublishedReview("o/r", 2, REVIEW));
        reader.comments.add(comment(10, REVIEW, null, "codemind-bot", ourBody));
        reader.comments.add(comment(11, 600L, 10L, "drive-by", "这条不对"));
        reader.comments.add(comment(12, 601L, 10L, "whitelisted", "不适用，因为…"));
        reader.unknowable.add("ghost");
        reader.reactions.put(10L, List.of(new ScmFeedbackReader.ReactionView(900, "lan-bin"),
                new ScmFeedbackReader.ReactionView(901, "drive-by"),
                new ScmFeedbackReader.ReactionView(902, "ghost"),
                new ScmFeedbackReader.ReactionView(903, "")));

        assertThat(service.collectRecent(Duration.ofDays(14))).isEqualTo(2);

        assertThat(ledger.pending()).extracting(FindingFeedbackReceived::sourceId).containsExactlyInAnyOrder(900L, 12L);
        // 同一轮同一账号只查一次权限；白名单与空账号不查
        assertThat(reader.permissionCalls).containsExactlyInAnyOrder("lan-bin", "drive-by", "ghost");
    }

    @Test
    void thumbsDownFromSomeoneWhoAlsoRepliedIsSupersededByTheReply() {
        // T6 实测：同一人既 👎 又回复，两者都学时 👎 概括出的反向规则把回复学到的规则删了
        ledger.published.add(new FindingFeedbackRepository.PublishedReview("o/r", 2, REVIEW));
        reader.comments.add(comment(10, REVIEW, null, "codemind-bot", ourBody));
        reader.comments.add(comment(11, 600L, 10L, "lan-bin", "SECURITY 不适用，sku 来自内部"));
        reader.writers.add("other-dev");
        reader.reactions.put(10L, List.of(new ScmFeedbackReader.ReactionView(900, "lan-bin"),
                new ScmFeedbackReader.ReactionView(901, "other-dev")));

        assertThat(service.collectRecent(Duration.ofDays(14))).isEqualTo(2);

        assertThat(ledger.pending()).extracting(FindingFeedbackReceived::feedbackType, FindingFeedbackReceived::sourceId)
                .containsExactlyInAnyOrder(org.assertj.core.groups.Tuple.tuple(FeedbackType.REPLY, 11L),
                        org.assertj.core.groups.Tuple.tuple(FeedbackType.THUMBS_DOWN, 901L));
        // 被取代的 👎 仍占位，再轮询不会当成新反馈
        assertThat(ledger.events).hasSize(3);
        assertThat(service.collectRecent(Duration.ofDays(14))).isZero();
    }

    @Test
    void oneFailingPullRequestDoesNotBlockOthers() {
        ledger.published.add(new FindingFeedbackRepository.PublishedReview("o/broken", 1, 1));
        ledger.published.add(new FindingFeedbackRepository.PublishedReview("o/r", 2, REVIEW));
        reader.failingRepo = "o/broken";
        reader.comments.add(comment(10, REVIEW, null, "codemind-bot", ourBody));
        reader.reactions.put(10L, List.of(new ScmFeedbackReader.ReactionView(900, "lan-bin")));

        assertThat(service.collectRecent(Duration.ofDays(14))).isEqualTo(1);
    }

    @Test
    void manualCollectOnlyTouchesRequestedPullRequest() {
        ledger.published.add(new FindingFeedbackRepository.PublishedReview("o/other", 9, 1));
        ledger.published.add(new FindingFeedbackRepository.PublishedReview("o/r", 2, REVIEW));
        reader.failingRepo = "o/other";  // 若被读到会抛异常
        reader.comments.add(comment(10, REVIEW, null, "codemind-bot", ourBody));
        reader.reactions.put(10L, List.of(new ScmFeedbackReader.ReactionView(900, "lan-bin")));

        assertThat(service.collect("o/r", 2, Duration.ofDays(14))).isEqualTo(1);
    }

    @Test
    void dispatchMarksSentOnlyAfterPublishAndKeepsFailuresPending() {
        ledger.published.add(new FindingFeedbackRepository.PublishedReview("o/r", 2, REVIEW));
        reader.comments.add(comment(10, REVIEW, null, "codemind-bot", ourBody));
        reader.reactions.put(10L, List.of(new ScmFeedbackReader.ReactionView(900, "lan-bin")));
        service.collectRecent(Duration.ofDays(14));

        publishFails = true;
        assertThat(service.dispatchPending()).isZero();
        assertThat(ledger.pending()).hasSize(1);
        assertThat(ledger.failures).hasSize(1);

        publishFails = false;
        assertThat(service.dispatchPending()).isEqualTo(1);
        assertThat(ledger.pending()).isEmpty();
        assertThat(published).hasSize(1);
        // 重投用登记时的同一 eventId，memory 侧据此幂等
        assertThat(((FindingFeedbackReceived) published.get(0)).eventId())
                .isEqualTo(ledger.events.values().iterator().next().eventId());
    }

    private static ScmFeedbackReader.ReviewCommentView comment(long id, Long reviewId, Long inReplyTo, String author,
                                                              String body) {
        return new ScmFeedbackReader.ReviewCommentView(id, reviewId, inReplyTo, author, body);
    }

    private static final class StubReader implements ScmFeedbackReader {
        final List<ReviewCommentView> comments = new ArrayList<>();
        final Map<Long, List<ReactionView>> reactions = new HashMap<>();
        final List<Long> reactionCalls = new ArrayList<>();
        String failingRepo;
        final java.util.Set<String> writers = new java.util.HashSet<>(java.util.Set.of("lan-bin"));
        final java.util.Set<String> unknowable = new java.util.HashSet<>();
        final List<String> permissionCalls = new ArrayList<>();

        @Override
        public boolean canWrite(String repo, String login) {
            permissionCalls.add(login);
            if (unknowable.contains(login)) {
                throw new ScmException("forbidden", 403);
            }
            return writers.contains(login);
        }

        @Override
        public List<ReviewCommentView> listReviewComments(String repo, int pr) {
            if (repo.equals(failingRepo)) {
                throw new ScmException("boom", 500);
            }
            return comments;
        }

        @Override
        public List<ReactionView> listThumbsDown(String repo, long commentId) {
            reactionCalls.add(commentId);
            return reactions.getOrDefault(commentId, List.of());
        }
    }

    private static final class InMemoryLedger implements FindingFeedbackRepository {
        final List<PublishedReview> published = new ArrayList<>();
        final Map<String, FindingFeedbackReceived> events = new LinkedHashMap<>();
        final Map<String, Boolean> sent = new HashMap<>();
        final List<String> failures = new ArrayList<>();
        Instant lastSince;

        final java.util.Set<String> superseded = new java.util.HashSet<>();

        @Override
        public boolean registerIfAbsent(FindingFeedbackReceived event, boolean isSuperseded) {
            String key = event.repo() + "|" + event.feedbackType() + "|" + event.sourceId();
            if (events.containsKey(key)) {
                return false;
            }
            events.put(key, event);
            if (isSuperseded) {
                superseded.add(event.eventId());
            }
            return true;
        }

        List<FindingFeedbackReceived> pending() {
            return events.values().stream().filter(e -> !sent.getOrDefault(e.eventId(), false))
                    .filter(e -> !superseded.contains(e.eventId())).toList();
        }

        @Override
        public List<FindingFeedbackReceived> findPending(int limit) {
            return pending().stream().limit(limit).toList();
        }

        @Override
        public boolean markSent(String eventId, Instant now) {
            return sent.put(eventId, true) == null;
        }

        @Override
        public void recordFailure(String eventId, String reason) {
            failures.add(eventId);
        }

        @Override
        public List<PublishedReview> findPublishedSince(Instant since) {
            lastSince = since;
            return published;
        }
    }
}
