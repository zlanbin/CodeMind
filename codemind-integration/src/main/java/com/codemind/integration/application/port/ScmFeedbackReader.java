package com.codemind.integration.application.port;

import java.util.List;

/**
 * 读 PR 上的反馈信号（M9）：行级评论及其回复、评论上的 👎。只读，与 {@link ScmReviewPublisher} 分开声明。
 *
 * <p>实现必须返回完整列表：分页超过安全上限时抛 {@link ScmException}，而不是悄悄截断。
 */
public interface ScmFeedbackReader {

    /** PR 上全部行级评论（含各 review 的评论与回复）。 */
    List<ReviewCommentView> listReviewComments(String repo, int pr);

    /** 一条行级评论上的全部 👎（reaction {@code -1}）。 */
    List<ReactionView> listThumbsDown(String repo, long commentId);

    /**
     * 该账号对仓库是否有写权限（协作者 / 成员）。不是协作者返回 false；平台不让查（如 token 权限不足）抛
     * {@link ScmException}，调用方按「不可信」处理。
     */
    boolean canWrite(String repo, String login);

    /**
     * @param reviewId    所属 review；GitHub 对极老的评论可能不给，为 null
     * @param inReplyToId 回复哪条评论；顶层评论为 null
     */
    record ReviewCommentView(long id, Long reviewId, Long inReplyToId, String authorLogin, String body) {
    }

    record ReactionView(long id, String authorLogin) {
    }
}
