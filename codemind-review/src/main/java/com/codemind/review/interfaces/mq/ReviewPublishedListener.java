package com.codemind.review.interfaces.mq;

import com.codemind.common.event.ReviewPublished;
import com.codemind.review.application.ReviewPublicationService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 消费 ReviewPublished，推进任务状态到 PUBLISHED（M7b）。
 *
 * <p>消息体是 JSON 字符串，由 integration 侧用 Boot 的 ObjectMapper 序列化；这里对称地手动
 * 反序列化，不依赖 starter 自带的 ObjectMapper（没注册 JavaTimeModule，{@code Instant} 字段会炸）。
 */
@Component
@RocketMQMessageListener(
        topic = "${codemind.mq.topic.review-published}",
        consumerGroup = "${codemind.mq.consumer-group.review-published}")
public class ReviewPublishedListener implements RocketMQListener<String> {

    private static final Logger log = LoggerFactory.getLogger(ReviewPublishedListener.class);

    private final ReviewPublicationService publicationService;
    private final ObjectMapper objectMapper;

    public ReviewPublishedListener(ReviewPublicationService publicationService, ObjectMapper objectMapper) {
        this.publicationService = publicationService;
        this.objectMapper = objectMapper;
    }

    @Override
    public void onMessage(String payload) {
        ReviewPublished event = deserialize(payload);
        boolean advanced = publicationService.markPublished(event);
        log.debug("收到 ReviewPublished eventId={} taskId={} advanced={}",
                event.eventId(), event.taskId(), advanced);
    }

    private ReviewPublished deserialize(String payload) {
        try {
            return objectMapper.readValue(payload, ReviewPublished.class);
        } catch (JsonProcessingException e) {
            // 抛出去让 RocketMQ 重试；连续失败会进死信队列，不会卡住消费位点。
            throw new IllegalStateException("ReviewPublished 反序列化失败", e);
        }
    }
}
