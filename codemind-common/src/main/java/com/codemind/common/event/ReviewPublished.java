package com.codemind.common.event;

import java.time.Instant;
import java.util.UUID;

/**
 * 评审发布回执事件。integration-service 成功回写后发出，review-service 据此推进任务状态。
 *
 * @param eventId     事件唯一 ID
 * @param occurredAt  事件发生时间
 * @param taskId      评审任务 ID
 * @param inlineCount 实际发布的行级评论数量
 * @param degraded    是否使用了降级发布结果
 */
public record ReviewPublished(
        String eventId,
        Instant occurredAt,
        String taskId,
        int inlineCount,
        boolean degraded
) implements DomainEvent {

    public ReviewPublished {
        if (inlineCount < 0) {
            throw new IllegalArgumentException("行级评论数量不能为负数: " + inlineCount);
        }
    }

    /** 用当前时间和随机 eventId 创建事件。 */
    public static ReviewPublished of(String taskId, int inlineCount, boolean degraded) {
        return new ReviewPublished(UUID.randomUUID().toString(), Instant.now(), taskId,
                inlineCount, degraded);
    }
}
