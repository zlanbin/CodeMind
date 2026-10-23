package com.codemind.review.application;

import com.codemind.review.domain.finding.TaskFingerprintPort;
import com.codemind.review.domain.publication.FingerprintRegistrationDelivery;
import com.codemind.review.domain.publication.FingerprintRegistrationOutboxRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;

/**
 * 把账本里的指纹登记到 memory（M10）。与 {@link ReviewOutboxDispatcher} 不同：这里的「发送」是直接
 * 同步调 {@link TaskFingerprintPort#record}，响应结果本身就是成败判定，不需要先发后等别处回执。
 */
public class FingerprintRegistrationDispatcher {
    private static final Logger log = LoggerFactory.getLogger(FingerprintRegistrationDispatcher.class);

    private final FingerprintRegistrationOutboxRepository outbox;
    private final TaskFingerprintPort taskFingerprintPort;
    private final Clock clock;
    private final int batchSize;
    private final Duration lease;
    private final Duration retryDelay;

    public FingerprintRegistrationDispatcher(FingerprintRegistrationOutboxRepository outbox,
                                             TaskFingerprintPort taskFingerprintPort, Clock clock,
                                             int batchSize, Duration lease, Duration retryDelay) {
        if (batchSize <= 0 || lease.isNegative() || lease.isZero()
                || retryDelay.isNegative() || retryDelay.isZero()) {
            throw new IllegalArgumentException("投递批量及时间配置必须为正");
        }
        this.outbox = outbox;
        this.taskFingerprintPort = taskFingerprintPort;
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

    private void send(FingerprintRegistrationDelivery delivery) {
        var reg = delivery.registration();
        boolean succeeded = false;
        String error = null;
        try {
            taskFingerprintPort.record(reg.repo(), reg.prNumber(), reg.fingerprints());
            succeeded = true;
        } catch (RuntimeException e) {
            // 不只兜 TaskFingerprintException：任意运行时异常都不能让 forEach 中断，否则
            // 批内排在后面的任务会被一条坏请求挡住（M10 Final Review F4）。
            error = e.getClass().getSimpleName();
            log.warn("指纹登记失败 taskId={} error={}", reg.taskId(), error);
        }
        try {
            if (succeeded) {
                outbox.acknowledge(reg.taskId(), clock.instant());
            } else {
                outbox.reschedule(reg.taskId(), delivery.leaseToken(), clock.instant().plus(retryDelay), error);
            }
        } catch (RuntimeException e) {
            // 原领取租约仍在；到期后可重领，不能因一条请求挡住本批其它任务。
            log.warn("指纹登记确认/重排失败 taskId={} error={}", reg.taskId(), e.getClass().getSimpleName());
        }
    }
}
