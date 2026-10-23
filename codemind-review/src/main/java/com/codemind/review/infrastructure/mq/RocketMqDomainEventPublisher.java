package com.codemind.review.infrastructure.mq;

import com.codemind.common.event.DomainEvent;
import com.codemind.common.event.ReviewCompleted;
import com.codemind.review.domain.event.DomainEventPublisher;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.messaging.Message;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * review 首次发消息（M7b）：此前只消费、不发布。
 *
 * <p>消息体自行用 Boot 的 ObjectMapper 序列化成 JSON 字符串再发——与 integration 侧
 * {@code RocketMqDomainEventPublisher} 同样的理由：starter 内置的 MessageConverter 用的是
 * 自带的 ObjectMapper，没注册 JavaTimeModule，直接发 record 会在 {@code Instant} 字段上抛异常。
 */
@Component
public class RocketMqDomainEventPublisher implements DomainEventPublisher {

    private final RocketMQTemplate rocketMQTemplate;
    private final ObjectMapper objectMapper;
    private final Map<Class<? extends DomainEvent>, String> topics;

    public RocketMqDomainEventPublisher(
            RocketMQTemplate rocketMQTemplate,
            ObjectMapper objectMapper,
            @Value("${codemind.mq.topic.review-completed}") String reviewCompletedTopic) {
        this.rocketMQTemplate = rocketMQTemplate;
        this.objectMapper = objectMapper;
        this.topics = Map.of(ReviewCompleted.class, reviewCompletedTopic);
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
