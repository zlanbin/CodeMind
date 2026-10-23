package com.codemind.memory.interfaces.mq;

import com.codemind.common.event.FindingFeedbackReceived;
import com.codemind.memory.application.FeedbackLearningService;
import com.codemind.memory.domain.feedback.FeedbackExtractor.ExtractionUnavailableException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 消费 {@link FindingFeedbackReceived}（M9）。消息体是 JSON 字符串（integration 用 Boot 的 mapper 序列化）。
 *
 * <p>抛异常 = 让 RocketMQ 重投：回复类反馈 LLM 不可用时宁可晚学，不按模板瞎学。
 * 反序列化失败同样抛出，死信策略见 PROGRESS 技术债（M1 遗留）。
 */
@Component
@RocketMQMessageListener(topic = "${codemind.mq.topic.finding-feedback}",
        consumerGroup = "${codemind.mq.consumer-group.finding-feedback}")
public class FindingFeedbackListener implements RocketMQListener<String> {

    private static final Logger log = LoggerFactory.getLogger(FindingFeedbackListener.class);

    private final FeedbackLearningService service;
    private final ObjectMapper objectMapper;

    public FindingFeedbackListener(FeedbackLearningService service, ObjectMapper objectMapper) {
        this.service = service;
        this.objectMapper = objectMapper;
    }

    @Override
    public void onMessage(String payload) {
        FindingFeedbackReceived event;
        try {
            event = objectMapper.readValue(payload, FindingFeedbackReceived.class);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("反馈事件无法反序列化", e);
        }
        try {
            var outcome = service.learn(event);
            log.debug("反馈已处理 eventId={} status={}", event.eventId(), outcome.status());
        } catch (ExtractionUnavailableException e) {
            log.warn("反馈暂不可学习，等待重投 eventId={} 原因={}", event.eventId(), e.getMessage());
            throw new IllegalStateException(e.getMessage(), e);
        }
    }
}
