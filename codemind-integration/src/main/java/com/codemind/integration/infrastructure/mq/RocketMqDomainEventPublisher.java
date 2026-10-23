package com.codemind.integration.infrastructure.mq;

import com.codemind.common.event.DomainEvent;
import com.codemind.common.event.PullRequestReceived;
import com.codemind.common.event.ReviewPublished;
import com.codemind.integration.domain.event.DomainEventPublisher;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.messaging.Message;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * RocketMQ 事件发布实现。
 *
 * <p>消息体自行用 Boot 的 ObjectMapper 序列化成 JSON 字符串再发：RocketMQ starter 内置的
 * MessageConverter 用的是自带的 ObjectMapper，没注册 JavaTimeModule，直接发 record 会在
 * {@code Instant} 字段上抛 InvalidDefinitionException。payload 是 String 时
 * RocketMQ 直接取字节，不会再过一遍 JSON 转换。
 */
@Component
public class RocketMqDomainEventPublisher implements DomainEventPublisher {

    private final RocketMQTemplate rocketMQTemplate;
    private final ObjectMapper objectMapper;
    private final Map<Class<? extends DomainEvent>, String> topics;

    public RocketMqDomainEventPublisher(
            RocketMQTemplate rocketMQTemplate,
            ObjectMapper objectMapper,
            @Value("${codemind.mq.topic.pull-request-received}") String pullRequestReceivedTopic,
            @Value("${codemind.mq.topic.review-published}") String reviewPublishedTopic) {
        this.rocketMQTemplate = rocketMQTemplate;
        this.objectMapper = objectMapper;
        this.topics = Map.of(
                PullRequestReceived.class, pullRequestReceivedTopic,
                ReviewPublished.class, reviewPublishedTopic);
    }

    @Override
    public void publish(DomainEvent event) {
        String topic = topics.get(event.getClass());
        if (topic == null) {
            throw new IllegalArgumentException("事件未配置 topic: " + event.getClass().getName());
        }
        Message<String> message = MessageBuilder.withPayload(serialize(event))
                .setHeader("KEYS", event.eventId())
                .build();
        rocketMQTemplate.syncSend(topic, message);
    }

    private String serialize(DomainEvent event) {
        try {
            return objectMapper.writeValueAsString(event);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("事件序列化失败: " + event.getClass().getName(), e);
        }
    }
}
