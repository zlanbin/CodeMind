package com.codemind.review.domain.event;

import com.codemind.common.event.DomainEvent;

public interface DomainEventPublisher {
    void publish(DomainEvent event);
}
