package com.codemind.review.support;

import java.util.List;

/**
 * 真实验收专用的 GitHub 只读/清理客户端——不是生产 SCM 实现，只给 {@code -Pgithub-write}
 * 的验收 IT 用。生产写路径仍是 integration 的 {@code GithubScmClient}。
 *
 * <p>GitHub REST API 对「已提交的 PR review」本身没有编辑 body 的端点（只能编辑 review 下的
 * 单条 comment，review 一旦提交只能 dismiss，不能改文字）——所以清理只删行级评论，
 * 不改 review 的总结 body。
 */
public interface SandboxGithubClient {

    /** 当前 token 对应的用户名（{@code GET /user}），用于判断某条 review/comment 是不是自己发的。 */
    String currentLogin();

    /** PR 当前 head commit 的完整 SHA（{@code GET /pulls/{n}}），用于发布前核对目标未变。 */
    String headSha(String repo, int prNumber);

    /** 列出 PR 的所有已提交 review（分页取全，不返回部分结果）。 */
    List<SandboxReview> listReviews(String repo, int prNumber);

    /** 列出 PR 的所有行级评论（分页取全，不返回部分结果）。 */
    List<SandboxComment> listComments(String repo, int prNumber);

    /** 删除一条行级评论。 */
    void deleteComment(String repo, long commentId);

    record SandboxReview(long id, String body, String authorLogin) { }

    /** {@code line} 为新侧行号；评论过期（outdated）时 GitHub 返回 null。 */
    record SandboxComment(long id, Long reviewId, String body, String authorLogin, String path, Integer line) { }
}
