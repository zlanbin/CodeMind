package com.codemind.integration.infrastructure.config;

import com.codemind.integration.application.FeedbackCollectionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.convert.DurationStyle;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * 反馈轮询定时器（M9）。由 {@code codemind.feedback.poll-enabled} 控制（生产默认开、测试关），
 * 手动入口 {@code POST /internal/feedback/poll} 不受它影响。
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "codemind.feedback.poll-enabled", havingValue = "true", matchIfMissing = true)
public class FeedbackConfig {

    private static final Logger log = LoggerFactory.getLogger(FeedbackConfig.class);

    private final FeedbackCollectionService service;
    private final String lookback;

    FeedbackConfig(FeedbackCollectionService service, @Value("${codemind.feedback.lookback:14d}") String lookback) {
        this.service = service;
        this.lookback = lookback;
    }

    /**
     * 先登记再投递，两步各自兜底：登记阶段出错（如库抖动）不能连累已登记的 PENDING 本轮发不出去（S6 规格轴）。
     */
    @Scheduled(fixedDelayString = "${codemind.feedback.poll-interval:5m}",
            initialDelayString = "${codemind.feedback.poll-initial-delay:1m}")
    void pollAndDispatch() {
        try {
            service.collectRecent(DurationStyle.detectAndParse(lookback));
        } catch (RuntimeException e) {
            log.warn("反馈登记失败 原因={}", e.getClass().getSimpleName());
        }
        try {
            service.dispatchPending();
        } catch (RuntimeException e) {
            log.warn("反馈投递失败 原因={}", e.getClass().getSimpleName());
        }
    }
}
