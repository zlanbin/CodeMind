package com.codemind.review.application;

import com.codemind.review.domain.event.DomainEventPublisher;
import com.codemind.review.domain.publication.OutboxDelivery;
import com.codemind.review.domain.publication.ReviewOutboxRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;

/** 数据库领取短事务结束后发 MQ；收到成功回执前一直保留请求。 */
public class ReviewOutboxDispatcher {
    private static final Logger log = LoggerFactory.getLogger(ReviewOutboxDispatcher.class);
    private final ReviewOutboxRepository outbox;
    private final DomainEventPublisher publisher;
    private final Clock clock;
    private final int batchSize;
    private final Duration lease;
    private final Duration retryDelay;

    public ReviewOutboxDispatcher(ReviewOutboxRepository outbox, DomainEventPublisher publisher,
                                 Clock clock, int batchSize, Duration lease, Duration retryDelay) {
        if (batchSize <= 0 || lease.isNegative() || lease.isZero()
                || retryDelay.isNegative() || retryDelay.isZero()) {
            throw new IllegalArgumentException("投递批量及时间配置必须为正");
        }
        this.outbox = outbox;
        this.publisher = publisher;
        this.clock = clock;
        this.batchSize = batchSize;
        this.lease = lease;
        this.retryDelay = retryDelay;
    }

    public int dispatchDue() {
        var deliveries = outbox.claimDue(clock.instant(), batchSize, lease);
        deliveries.forEach(this::send);
        return deliveries.size();
    }

    public boolean dispatchTask(String taskId) {
        var delivery = outbox.claimTask(taskId, clock.instant(), lease);
        delivery.ifPresent(this::send);
        return delivery.isPresent();
    }

    private void send(OutboxDelivery delivery) {
        String error = null;
        try {
            publisher.publish(delivery.event());
        } catch (RuntimeException e) {
            // 不保存异常正文（可能来自外部传输），只保留类型便于定位。
            error = e.getClass().getSimpleName();
            log.warn("评审完成请求投递失败 taskId={} error={}", delivery.event().taskId(), error);
        }
        try {
            outbox.reschedule(delivery.event().taskId(), delivery.leaseToken(),
                    clock.instant().plus(retryDelay), error);
        } catch (RuntimeException e) {
            // 原领取租约仍在；到期后可重领，不能因一条请求挡住本批其它任务。
            log.warn("投递请求重排失败 taskId={} error={}", delivery.event().taskId(), e.getClass().getSimpleName());
        }
    }
}
