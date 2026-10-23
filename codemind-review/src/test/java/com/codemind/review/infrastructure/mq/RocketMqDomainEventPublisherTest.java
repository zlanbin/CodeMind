package com.codemind.review.infrastructure.mq;

import com.codemind.common.event.DomainEvent;
import com.codemind.common.event.ReviewCompleted;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.Message;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * review 侧第一次发消息（M7b）：此前只消费、不发布。沿用 integration 侧已验证的做法——
 * 手动用 Boot 的 ObjectMapper 序列化成 JSON 字符串，不走 starter 自带的 MessageConverter。
 */
class RocketMqDomainEventPublisherTest {

    private final RocketMQTemplate rocketMQTemplate = mock(RocketMQTemplate.class);
    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());

    @Test
    void sendsReviewCompletedAsJsonStringWithEventIdKey() {
        var publisher = new RocketMqDomainEventPublisher(rocketMQTemplate, mapper, "review-completed-topic");
        ReviewCompleted event = ReviewCompleted.of("task-1", "owner/repo", 7, "head", "summary",
                List.of(new ReviewCompleted.LineComment("A.java", 11, "issue")));

        publisher.publish(event);

        ArgumentCaptorMessage captor = captureMessage(rocketMQTemplate, "review-completed-topic");
        assertInstanceOf(String.class, captor.message.getPayload());
        assertTrue(((String) captor.message.getPayload()).contains("\"taskId\":\"task-1\""),
                "payload 应是 ReviewCompleted 的 JSON 字符串");
        assertEquals(event.eventId(), captor.message.getHeaders().get("KEYS"));
    }

    @Test
    void instantRoundTripsThroughJsonWithoutException() throws Exception {
        var publisher = new RocketMqDomainEventPublisher(rocketMQTemplate, mapper, "topic");
        ReviewCompleted event = ReviewCompleted.of("task-1", "owner/repo", 7, "head", "summary", List.of());

        publisher.publish(event);

        var captor = captureMessage(rocketMQTemplate, "topic");
        ReviewCompleted roundTripped = mapper.readValue((String) captor.message.getPayload(), ReviewCompleted.class);
        assertEquals(event.occurredAt(), roundTripped.occurredAt(),
                "Instant 字段要能正确往返，没注册 JavaTimeModule 的 mapper 会在这里抛异常");
    }

    @Test
    void unknownEventTypeIsRejected() {
        var publisher = new RocketMqDomainEventPublisher(rocketMQTemplate, mapper, "topic");
        DomainEvent unknown = new DomainEvent() {
            public String eventId() { return "x"; }
            public java.time.Instant occurredAt() { return java.time.Instant.now(); }
        };

        assertThrows(IllegalArgumentException.class, () -> publisher.publish(unknown));
        verify(rocketMQTemplate, never()).syncSend(any(), any(Message.class));
    }

    private record ArgumentCaptorMessage(Message<?> message) { }

    @SuppressWarnings("unchecked")
    private static ArgumentCaptorMessage captureMessage(RocketMQTemplate template, String expectedTopic) {
        org.mockito.ArgumentCaptor<Message<String>> captor = org.mockito.ArgumentCaptor.forClass(Message.class);
        verify(template).syncSend(eq(expectedTopic), captor.capture());
        return new ArgumentCaptorMessage(captor.getValue());
    }
}
