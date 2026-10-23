package com.codemind.integration.infrastructure.mq;

import com.codemind.common.event.DomainEvent;
import com.codemind.common.event.FindingFeedbackReceived;
import com.codemind.common.event.PullRequestReceived;
import com.codemind.common.event.ReviewPublished;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.Message;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * S6 评审 I-2（Task 9）：review 侧有这份对称单测，integration 侧当时漏补——
 * 本任务给这个类加了第四个构造参数和 ReviewPublished 映射，只被
 * {@code ReviewCompletedListenerIT} 间接覆盖，PullRequestReceived 映射与未知事件拒绝未验。
 */
class RocketMqDomainEventPublisherTest {

    private final RocketMQTemplate rocketMQTemplate = mock(RocketMQTemplate.class);
    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());

    @Test
    void sendsPullRequestReceivedAsJsonStringWithEventIdKey() {
        var publisher = new RocketMqDomainEventPublisher(rocketMQTemplate, mapper,
                "pr-received-topic", "review-published-topic", "finding-feedback-topic");
        PullRequestReceived event = PullRequestReceived.of("delivery-1", "opened",
                "owner/repo", 7, "head", "base");

        publisher.publish(event);

        var captor = captureMessage(rocketMQTemplate, "pr-received-topic");
        assertInstanceOf(String.class, captor.getPayload());
        assertTrue(((String) captor.getPayload()).contains("\"repo\":\"owner/repo\""));
        assertEquals(event.eventId(), captor.getHeaders().get("KEYS"));
    }

    @Test
    void sendsReviewPublishedAsJsonStringWithEventIdKey() {
        var publisher = new RocketMqDomainEventPublisher(rocketMQTemplate, mapper,
                "pr-received-topic", "review-published-topic", "finding-feedback-topic");
        ReviewPublished event = ReviewPublished.of("task-1", 3, false);

        publisher.publish(event);

        var captor = captureMessage(rocketMQTemplate, "review-published-topic");
        assertTrue(((String) captor.getPayload()).contains("\"taskId\":\"task-1\""));
        assertEquals(event.eventId(), captor.getHeaders().get("KEYS"));
    }

    @Test
    void sendsFindingFeedbackToItsOwnTopicAndRoundTrips() throws Exception {
        var publisher = new RocketMqDomainEventPublisher(rocketMQTemplate, mapper,
                "pr-received-topic", "review-published-topic", "finding-feedback-topic");
        FindingFeedbackReceived event = FindingFeedbackReceived.of("owner/repo", 2, 55L,
                FindingFeedbackReceived.FeedbackType.THUMBS_DOWN, 9L, "lan-bin", null, "意见",
                java.util.List.of(new FindingFeedbackReceived.FindingRef("c".repeat(64), "STYLE")));

        publisher.publish(event);

        var captor = captureMessage(rocketMQTemplate, "finding-feedback-topic");
        assertEquals(event, mapper.readValue((String) captor.getPayload(), FindingFeedbackReceived.class));
        assertEquals(event.eventId(), captor.getHeaders().get("KEYS"));
    }

    @Test
    void instantRoundTripsThroughJsonWithoutException() throws Exception {
        var publisher = new RocketMqDomainEventPublisher(rocketMQTemplate, mapper,
                "pr-received-topic", "review-published-topic", "finding-feedback-topic");
        ReviewPublished event = ReviewPublished.of("task-1", 1, false);

        publisher.publish(event);

        var captor = captureMessage(rocketMQTemplate, "review-published-topic");
        ReviewPublished roundTripped = mapper.readValue((String) captor.getPayload(), ReviewPublished.class);
        assertEquals(event.occurredAt(), roundTripped.occurredAt(),
                "Instant 字段要能正确往返，没注册 JavaTimeModule 的 mapper 会在这里抛异常");
    }

    @Test
    void unknownEventTypeIsRejected() {
        var publisher = new RocketMqDomainEventPublisher(rocketMQTemplate, mapper,
                "pr-received-topic", "review-published-topic", "finding-feedback-topic");
        DomainEvent unknown = new DomainEvent() {
            public String eventId() { return "x"; }
            public java.time.Instant occurredAt() { return java.time.Instant.now(); }
        };

        assertThrows(IllegalArgumentException.class, () -> publisher.publish(unknown));
        verify(rocketMQTemplate, never()).syncSend(any(), any(Message.class));
    }

    @SuppressWarnings("unchecked")
    private static Message<?> captureMessage(RocketMQTemplate template, String expectedTopic) {
        org.mockito.ArgumentCaptor<Message<String>> captor =
                org.mockito.ArgumentCaptor.forClass((Class) Message.class);
        verify(template).syncSend(eq(expectedTopic), captor.capture());
        return captor.getValue();
    }
}
