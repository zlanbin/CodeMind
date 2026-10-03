package com.codemind.common.event;

import java.time.Instant;
import java.util.UUID;

/**
 * PR 创建或更新事件。integration-service 验签幂等通过后发出，review-service 消费后建评审任务。
 *
 * @param eventId    事件唯一 ID，消费端幂等用
 * @param occurredAt 事件发生时间
 * @param deliveryId Webhook 投递 ID（GitHub 的 X-GitHub-Delivery），排查链路用
 * @param action     PR 动作，只会是 opened 或 synchronize
 * @param repo       仓库全名，形如 owner/name
 * @param prNumber   PR 序号
 * @param headSha    PR 源分支 HEAD commit SHA
 * @param baseSha    目标分支基线 commit SHA
 */
public record PullRequestReceived(
        String eventId,
        Instant occurredAt,
        String deliveryId,
        String action,
        String repo,
        int prNumber,
        String headSha,
        String baseSha
) implements DomainEvent {

    /** 用当前时间和随机 eventId 创建事件。 */
    public static PullRequestReceived of(String deliveryId, String action, String repo,
                                         int prNumber, String headSha, String baseSha) {
        return new PullRequestReceived(UUID.randomUUID().toString(), Instant.now(),
                deliveryId, action, repo, prNumber, headSha, baseSha);
    }
}
