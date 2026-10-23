package com.codemind.common.event;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * 评审完成事件。review-service 持久化并投递，integration-service 消费后回写 PR。
 *
 * @param eventId     事件唯一 ID
 * @param occurredAt  事件发生时间
 * @param taskId      评审任务 ID
 * @param repo        仓库全名，形如 owner/name
 * @param prNumber    PR 序号
 * @param headSha     评审所针对的源分支 HEAD commit SHA
 * @param summaryBody 总结评论正文
 * @param comments    行级评论
 */
public record ReviewCompleted(
        String eventId,
        Instant occurredAt,
        String taskId,
        String repo,
        int prNumber,
        String headSha,
        String summaryBody,
        List<LineComment> comments
) implements DomainEvent {

    public ReviewCompleted {
        comments = List.copyOf(Objects.requireNonNull(comments, "comments"));
    }

    /** 用当前时间和随机 eventId 创建事件。 */
    public static ReviewCompleted of(String taskId, String repo, int prNumber, String headSha,
                                     String summaryBody, List<LineComment> comments) {
        return new ReviewCompleted(UUID.randomUUID().toString(), Instant.now(), taskId, repo,
                prNumber, headSha, summaryBody, comments);
    }

    /** 一条 GitHub 行级评论的公共表示，不耦合平台请求类型。 */
    public record LineComment(String path, int line, String body) {

        public LineComment {
            if (line <= 0) {
                throw new IllegalArgumentException("行号必须大于 0: " + line);
            }
        }
    }
}
