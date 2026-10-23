package com.codemind.common.event;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * 开发者对一条 CodeMind 行级评论的反馈（M9）。integration 轮询 GitHub 后发出，memory 消费后学习否决记忆。
 *
 * @param eventId      事件唯一 ID；同一反馈重投用同一个 ID
 * @param occurredAt   事件发生时间
 * @param repo         仓库全名，即 memory 的 project
 * @param prNumber     PR 序号
 * @param commentId    被反馈的 CodeMind 行级评论 ID
 * @param feedbackType 👎 或回复
 * @param sourceId     reaction ID 或回复评论 ID
 * @param author       反馈人登录名
 * @param replyText    回复正文；👎 时为 null
 * @param commentBody  被反馈的评论正文（已去掉 Finding 标记）
 * @param findings     评论里合并的各条 Finding 的指纹与类别，至少一条
 */
public record FindingFeedbackReceived(
        String eventId,
        Instant occurredAt,
        String repo,
        int prNumber,
        long commentId,
        FeedbackType feedbackType,
        long sourceId,
        String author,
        String replyText,
        String commentBody,
        List<FindingRef> findings
) implements DomainEvent {

    public FindingFeedbackReceived {
        Objects.requireNonNull(feedbackType, "feedbackType");
        findings = List.copyOf(Objects.requireNonNull(findings, "findings"));
        if (findings.isEmpty()) {
            throw new IllegalArgumentException("反馈至少要对应一条 Finding");
        }
    }

    public static FindingFeedbackReceived of(String repo, int prNumber, long commentId, FeedbackType feedbackType,
                                             long sourceId, String author, String replyText, String commentBody,
                                             List<FindingRef> findings) {
        return new FindingFeedbackReceived(UUID.randomUUID().toString(), Instant.now(), repo, prNumber, commentId,
                feedbackType, sourceId, author, replyText, commentBody, findings);
    }

    public enum FeedbackType {
        /** GitHub reaction {@code -1}。 */
        THUMBS_DOWN,
        /** 对评论的回复，是否构成否决由 memory 判断。 */
        REPLY
    }

    public record FindingRef(String fingerprint, String category) {
    }
}
