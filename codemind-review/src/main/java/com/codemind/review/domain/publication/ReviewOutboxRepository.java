package com.codemind.review.domain.publication;

import com.codemind.common.event.ReviewCompleted;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** 评审完成请求的可靠投递端口；MQ 送达不代表发布成功。 */
public interface ReviewOutboxRepository {
    Optional<OutboxEntry> findByTaskId(String taskId);
    void insert(ReviewCompleted event);
    List<OutboxDelivery> claimDue(Instant now, int limit, Duration lease);
    Optional<OutboxDelivery> claimTask(String taskId, Instant now, Duration lease);
    boolean reschedule(String taskId, String leaseToken, Instant nextAt, String error);
    boolean abandon(String taskId, String leaseToken, Instant now, String error);
    void acknowledge(String taskId, Instant now);
}
