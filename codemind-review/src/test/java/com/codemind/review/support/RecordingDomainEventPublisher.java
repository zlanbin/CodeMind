package com.codemind.review.support;

import com.codemind.common.event.DomainEvent;
import com.codemind.review.domain.event.DomainEventPublisher;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** 普通 IT 的事件出口，绝不投递到真实发布链路。 */
public class RecordingDomainEventPublisher implements DomainEventPublisher {
    private final List<DomainEvent> events = new CopyOnWriteArrayList<>();
    @Override public void publish(DomainEvent event) { events.add(event); }
    public List<DomainEvent> events() { return List.copyOf(events); }
}
