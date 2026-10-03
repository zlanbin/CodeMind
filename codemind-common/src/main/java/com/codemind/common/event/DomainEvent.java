package com.codemind.common.event;

import java.time.Instant;

/**
 * 跨服务领域事件的公共契约。具体事件（如 PullRequestReceived）从 M1 起在本包中定义。
 */
public interface DomainEvent {

    /** 事件唯一 ID，用于消费端幂等。 */
    String eventId();

    /** 事件发生时间。 */
    Instant occurredAt();
}
