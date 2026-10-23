package com.codemind.review.support;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 真实验收前清理上一轮测试痕迹——仅限 {@code -Pgithub-write} 验收 IT，不是生产代码。
 *
 * <p>GitHub 对已提交 review 没有改 body 的端点（见 {@link SandboxGithubClient} 的说明），
 * 所以这里只删行级评论，不碰 review 本身的总结。候选判定严格收紧：只有 review body
 * 同时带生产标记（{@code codemind:task=}）和测试专用标记（{@code codemind:test=github-write}）、
 * 作者是当前 token 用户、且对应任务不在受保护集合（发送结果未定）里，才算候选；
 * 该候选下、同样由当前用户发出的行级评论才会被删除。
 *
 * <p>删除分两步：{@link #listCommentsPendingDeletion()} 只读列出给用户确认；
 * {@link #deleteConfirmed(Set)} 重新计算候选，必须与用户确认的 id 集合完全一致才删。
 */
public final class SandboxReviewCleanup {

    /** 测试专用标记，与生产的 {@code codemind:task=} 标记分开，防止误删真实使用场景产出的 review。 */
    public static final String TEST_MARKER = "<!-- codemind:test=github-write -->";
    private static final String PRODUCTION_MARKER_PREFIX = "<!-- codemind:task=";
    private static final Pattern TASK_ID = Pattern.compile("<!-- codemind:task=(\\S+) -->");

    private final SandboxGithubClient client;
    private final String repo;
    private final int prNumber;
    private final Set<String> protectedTaskIds;

    /** @param protectedTaskIds 发送账本处于 IN_FLIGHT/UNCERTAIN 的任务，其 review 是核查证据，不能动 */
    public SandboxReviewCleanup(SandboxGithubClient client, String repo, int prNumber, Set<String> protectedTaskIds) {
        this.client = client;
        this.repo = repo;
        this.prNumber = prNumber;
        this.protectedTaskIds = Set.copyOf(protectedTaskIds);
    }

    /** 只读列出待删评论，不实际删除——首次清理门禁要求先展示再等用户确认。 */
    public List<SandboxGithubClient.SandboxComment> listCommentsPendingDeletion() {
        String me = client.currentLogin();
        List<SandboxGithubClient.SandboxReview> reviews = client.listReviews(repo, prNumber);
        List<SandboxGithubClient.SandboxComment> comments = client.listComments(repo, prNumber);
        return commentsToDelete(comments, candidateReviews(reviews, me, protectedTaskIds), me);
    }

    /**
     * 删除用户确认过的评论。重新读取并计算候选，与 {@code confirmedIds} 不完全一致（多了或少了）
     * 就整体放弃、不删任何一条；分页读取失败同样整体放弃。返回被删除的评论 id。
     */
    public List<Long> deleteConfirmed(Set<Long> confirmedIds) {
        List<SandboxGithubClient.SandboxComment> toDelete = listCommentsPendingDeletion();
        Set<Long> current = toDelete.stream().map(SandboxGithubClient.SandboxComment::id).collect(Collectors.toSet());
        if (!current.equals(new HashSet<>(confirmedIds))) {
            throw new IllegalStateException("待删候选已变化，确认的是 " + confirmedIds + "，当前是 " + current
                    + "；停下重新列出并确认");
        }
        for (var comment : toDelete) {
            client.deleteComment(repo, comment.id());
        }
        return toDelete.stream().map(SandboxGithubClient.SandboxComment::id).toList();
    }

    /** 只有同时带生产标记与测试标记、作者是当前用户、且任务不受保护的 review 才算候选。 */
    static List<SandboxGithubClient.SandboxReview> candidateReviews(
            List<SandboxGithubClient.SandboxReview> reviews, String currentLogin, Set<String> protectedTaskIds) {
        return reviews.stream()
                .filter(r -> currentLogin.equals(r.authorLogin()))
                .filter(r -> isTestMarked(r.body()))
                .filter(r -> !protectedTaskIds.contains(taskIdOf(r.body())))
                .toList();
    }

    /** 候选 review 下、由当前用户发出的行级评论才删；他人回复或不相关 review 下的评论都不动。 */
    static List<SandboxGithubClient.SandboxComment> commentsToDelete(
            List<SandboxGithubClient.SandboxComment> comments,
            List<SandboxGithubClient.SandboxReview> candidates, String currentLogin) {
        Set<Long> candidateIds = candidates.stream()
                .map(SandboxGithubClient.SandboxReview::id)
                .collect(Collectors.toSet());
        return comments.stream()
                .filter(c -> c.reviewId() != null && candidateIds.contains(c.reviewId()))
                .filter(c -> currentLogin.equals(c.authorLogin()))
                .toList();
    }

    private static boolean isTestMarked(String body) {
        return body != null && body.contains(PRODUCTION_MARKER_PREFIX) && body.contains(TEST_MARKER);
    }

    private static String taskIdOf(String body) {
        Matcher m = TASK_ID.matcher(body);
        return m.find() ? m.group(1) : "";
    }
}
