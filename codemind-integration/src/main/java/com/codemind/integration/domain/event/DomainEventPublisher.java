package com.codemind.integration.domain.event;

import com.codemind.common.event.DomainEvent;

/**
 * 领域事件发布端口，实现在 infrastructure 层（RocketMQ）。
 */
public interface DomainEventPublisher {

    void publish(DomainEvent event);
}
