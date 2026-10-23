package com.codemind.review.support;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 清理策略的纯逻辑测试（不碰真实 GitHub）：只有作者是当前 token 用户、review body 同时带生产标记
 * （{@code codemind:task=}）和测试专用标记（{@code codemind:test=github-write}）才列为候选；排除他人回复、
 * 非测试 review、以及本地发送账本仍处于 IN_FLIGHT/UNCERTAIN 的任务；分页读失败不删任何东西；
 * 删除必须带上用户确认过的 id 集合，与重新计算的候选不一致就整体放弃。
 */
class SandboxReviewCleanupTest {

    private static final String SANDBOX_REPO = "zlanbin/codemind-sandbox";
    private static final String ME = "zlanbin";

    @Test
    void onlyOwnTestMarkedReviewsAreCandidates() {
        var client = new FakeClient();
        client.reviews.add(review(1, testMarkedBody("task-1"), ME));
        client.reviews.add(review(2, "没有任何标记的别人评论", "someone-else"));
        client.reviews.add(review(3, prodOnlyBody("task-3"), ME)); // 生产标记但没有测试标记：不是本工具发的测试数据

        var candidates = SandboxReviewCleanup.candidateReviews(client.reviews, ME, Set.of());

        assertEquals(List.of(1L), candidates.stream().map(SandboxGithubClient.SandboxReview::id).toList());
    }

    @Test
    void reviewsByOtherAuthorsAreExcludedEvenIfMarked() {
        var client = new FakeClient();
        client.reviews.add(review(1, testMarkedBody("task-1"), "someone-else"));

        assertTrue(SandboxReviewCleanup.candidateReviews(client.reviews, ME, Set.of()).isEmpty(),
                "标记对了但作者不是当前 token 用户，不能列为候选");
    }

    @Test
    void reviewsOfTasksStillInFlightOrUncertainAreNotCandidates() {
        var client = new FakeClient();
        client.reviews.add(review(1, testMarkedBody("task-settled"), ME));
        client.reviews.add(review(2, testMarkedBody("task-in-flight"), ME));
        client.comments.add(comment(10, 1L, ME));
        client.comments.add(comment(20, 2L, ME));

        var cleanup = new SandboxReviewCleanup(client, SANDBOX_REPO, 1, Set.of("task-in-flight"));

        assertEquals(List.of(10L), ids(cleanup.listCommentsPendingDeletion()),
                "发送结果未定的任务正在被核查，它的评论是证据，不能删");
    }

    @Test
    void commentsUnderNonCandidateReviewsAreNotTouched() {
        var client = new FakeClient();
        client.reviews.add(review(1, testMarkedBody("task-1"), ME));
        client.comments.add(comment(10, 1L, ME));
        client.comments.add(comment(11, 99L, ME));

        var toDelete = SandboxReviewCleanup.commentsToDelete(
                client.comments, SandboxReviewCleanup.candidateReviews(client.reviews, ME, Set.of()), ME);

        assertEquals(List.of(10L), ids(toDelete));
    }

    @Test
    void commentsByOtherAuthorsUnderOwnCandidateReviewAreNotDeleted() {
        var client = new FakeClient();
        client.reviews.add(review(1, testMarkedBody("task-1"), ME));
        client.comments.add(comment(10, 1L, ME));
        client.comments.add(comment(11, 1L, "someone-else"));

        var toDelete = SandboxReviewCleanup.commentsToDelete(
                client.comments, SandboxReviewCleanup.candidateReviews(client.reviews, ME, Set.of()), ME);

        assertEquals(List.of(10L), ids(toDelete));
    }

    @Test
    void listingIsReadOnly() {
        var client = new FakeClient();
        client.reviews.add(review(1, testMarkedBody("task-1"), ME));
        client.comments.add(comment(10, 1L, ME));

        new SandboxReviewCleanup(client, SANDBOX_REPO, 1, Set.of()).listCommentsPendingDeletion();

        assertTrue(client.deletedIds.isEmpty(), "列出候选是门禁前的只读步骤，不能删除任何东西");
    }

    @Test
    void incompleteListingAbortsCleanupEntirely() {
        var client = new FakeClient();
        client.reviews.add(review(1, testMarkedBody("task-1"), ME));
        client.failListComments = true;

        assertThrows(IllegalStateException.class, () -> new SandboxReviewCleanup(client, SANDBOX_REPO, 1, Set.of())
                .deleteConfirmed(Set.of(10L)), "分页读取失败时必须整体放弃，绝不能基于部分结果删除");
        assertTrue(client.deletedIds.isEmpty(), "读取失败时不能删除任何评论");
    }

    @Test
    void deletesExactlyTheConfirmedCandidates() {
        var client = new FakeClient();
        client.reviews.add(review(1, testMarkedBody("task-1"), ME));
        client.reviews.add(review(2, "别人的", "someone-else"));
        client.comments.add(comment(10, 1L, ME));
        client.comments.add(comment(20, 2L, "someone-else"));

        List<Long> deleted = new SandboxReviewCleanup(client, SANDBOX_REPO, 1, Set.of()).deleteConfirmed(Set.of(10L));

        assertEquals(List.of(10L), deleted);
        assertEquals(List.of(10L), client.deletedIds);
    }

    @Test
    void candidatesChangedSinceConfirmationDeleteNothing() {
        var client = new FakeClient();
        client.reviews.add(review(1, testMarkedBody("task-1"), ME));
        client.comments.add(comment(10, 1L, ME));
        client.comments.add(comment(11, 1L, ME)); // 用户确认之后新出现的候选

        var cleanup = new SandboxReviewCleanup(client, SANDBOX_REPO, 1, Set.of());

        assertThrows(IllegalStateException.class, () -> cleanup.deleteConfirmed(Set.of(10L)),
                "候选集合与用户确认的不一致，必须停下重新确认");
        assertTrue(client.deletedIds.isEmpty());
    }

    @Test
    void confirmingAnIdThatIsNotACandidateDeletesNothing() {
        var client = new FakeClient();
        client.reviews.add(review(1, prodOnlyBody("task-1"), ME));
        client.comments.add(comment(10, 1L, ME)); // 生产 review 下的评论，确认了也不能删

        var cleanup = new SandboxReviewCleanup(client, SANDBOX_REPO, 1, Set.of());

        assertThrows(IllegalStateException.class, () -> cleanup.deleteConfirmed(Set.of(10L)));
        assertTrue(client.deletedIds.isEmpty());
    }

    private static List<Long> ids(List<SandboxGithubClient.SandboxComment> comments) {
        return comments.stream().map(SandboxGithubClient.SandboxComment::id).toList();
    }

    private static SandboxGithubClient.SandboxReview review(long id, String body, String author) {
        return new SandboxGithubClient.SandboxReview(id, body, author);
    }

    private static SandboxGithubClient.SandboxComment comment(long id, Long reviewId, String author) {
        return new SandboxGithubClient.SandboxComment(id, reviewId, "body-" + id, author, "src/A.java", 1);
    }

    private static String testMarkedBody(String taskId) {
        return "summary\n\n" + SandboxReviewCleanup.TEST_MARKER + "\n\n<!-- codemind:task=" + taskId + " -->";
    }

    private static String prodOnlyBody(String taskId) {
        return "summary\n\n<!-- codemind:task=" + taskId + " -->";
    }

    private static final class FakeClient implements SandboxGithubClient {
        final List<SandboxReview> reviews = new java.util.ArrayList<>();
        final List<SandboxComment> comments = new java.util.ArrayList<>();
        final List<Long> deletedIds = new java.util.ArrayList<>();
        boolean failListComments;

        @Override public String currentLogin() { return ME; }
        @Override public String headSha(String repo, int prNumber) { return "0".repeat(40); }
        @Override public List<SandboxReview> listReviews(String repo, int prNumber) { return List.copyOf(reviews); }
        @Override public List<SandboxComment> listComments(String repo, int prNumber) {
            if (failListComments) throw new IllegalStateException("分页读取失败（模拟）");
            return List.copyOf(comments);
        }
        @Override public void deleteComment(String repo, long commentId) { deletedIds.add(commentId); }
    }
}
