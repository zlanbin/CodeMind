package com.codemind.review.interfaces.mq;

import com.codemind.common.event.PullRequestReceived;
import com.codemind.review.application.ReviewExecutionService;
import com.codemind.review.application.ReviewTaskApplicationService;
import com.codemind.review.domain.task.ReviewTask;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 消费 PullRequestReceived，建评审任务。
 *
 * <p>消息体是 JSON 字符串，由生产端用 Boot 的 ObjectMapper 序列化；这里对称地手动反序列化，
 * 不依赖 starter 自带的 ObjectMapper（它没注册 JavaTimeModule，{@code Instant} 字段会炸）。
 */
@Component
@RocketMQMessageListener(
        topic = "${codemind.mq.topic.pull-request-received}",
        consumerGroup = "${codemind.mq.consumer-group.pull-request-received}")
public class PullRequestReceivedListener implements RocketMQListener<String> {

    private static final Logger log = LoggerFactory.getLogger(PullRequestReceivedListener.class);

    private final ReviewTaskApplicationService reviewTaskApplicationService;
    private final ReviewExecutionService reviewExecutionService;
    private final ObjectMapper objectMapper;

    public PullRequestReceivedListener(ReviewTaskApplicationService reviewTaskApplicationService,
                                       ReviewExecutionService reviewExecutionService,
                                       ObjectMapper objectMapper) {
        this.reviewTaskApplicationService = reviewTaskApplicationService;
        this.reviewExecutionService = reviewExecutionService;
        this.objectMapper = objectMapper;
    }

    @Override
    public void onMessage(String payload) {
        PullRequestReceived event = deserialize(payload);
        log.debug("收到 PullRequestReceived eventId={} repo={} pr={}",
                event.eventId(), event.repo(), event.prNumber());
        ReviewTask task = reviewTaskApplicationService.createTaskFrom(event);
        // 异步触发：评审要等几十秒 LLM，阻塞消费线程会触发消息重投。
        // 重复投递的判重在 ReviewExecutionService（按任务状态），这里不做判断。
        reviewExecutionService.submitAsync(task.taskId());
    }

    private PullRequestReceived deserialize(String payload) {
        try {
            return objectMapper.readValue(payload, PullRequestReceived.class);
        } catch (JsonProcessingException e) {
            // 抛出去让 RocketMQ 重试；连续失败会进死信队列，不会卡住消费位点。
            throw new IllegalStateException("PullRequestReceived 反序列化失败", e);
        }
    }
}
