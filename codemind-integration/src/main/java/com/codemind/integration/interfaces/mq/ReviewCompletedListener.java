package com.codemind.integration.interfaces.mq;

import com.codemind.common.event.ReviewCompleted;
import com.codemind.integration.application.ReviewPublishService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 消费 ReviewCompleted，调保守发布状态机（M7b）。
 *
 * <p>消息体是 JSON 字符串，由 review 侧用 Boot 的 ObjectMapper 序列化；这里对称地手动
 * 反序列化，不依赖 starter 自带的 ObjectMapper。
 */
@Component
@RocketMQMessageListener(
        topic = "${codemind.mq.topic.review-completed}",
        consumerGroup = "${codemind.mq.consumer-group.review-completed}")
public class ReviewCompletedListener implements RocketMQListener<String> {

    private static final Logger log = LoggerFactory.getLogger(ReviewCompletedListener.class);

    private final ReviewPublishService publishService;
    private final ObjectMapper objectMapper;

    public ReviewCompletedListener(ReviewPublishService publishService, ObjectMapper objectMapper) {
        this.publishService = publishService;
        this.objectMapper = objectMapper;
    }

    @Override
    public void onMessage(String payload) {
        ReviewCompleted event = deserialize(payload);
        var outcome = publishService.publish(event);
        log.debug("收到 ReviewCompleted eventId={} taskId={} outcome={}",
                event.eventId(), event.taskId(), outcome);
    }

    private ReviewCompleted deserialize(String payload) {
        try {
            return objectMapper.readValue(payload, ReviewCompleted.class);
        } catch (JsonProcessingException e) {
            // 抛出去让 RocketMQ 重试；连续失败会进死信队列，不会卡住消费位点。
            throw new IllegalStateException("ReviewCompleted 反序列化失败", e);
        }
    }
}
