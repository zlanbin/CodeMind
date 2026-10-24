package com.codemind.review.domain.publication;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * 指纹登记的可靠投递端口（M10）。不同于 {@link ReviewOutboxRepository}（发 MQ、等别处回执），
 * 这里的「发送」是直接调 memory 的同步 HTTP 调用，响应码本身就是成败判定——没有「发了但不确定
 * 对方收到没」的不确定态，不需要 outbox+MQ+回执两阶段模式。
 */
public interface FingerprintRegistrationOutboxRepository {

    /** {@code task_id} 主键天然幂等：重复回执（MQ at-least-once）不应插出第二行，也不应抛异常中断主流程。 */
    void insert(String taskId, String repo, int prNumber, List<String> fingerprints);

    List<FingerprintRegistrationDelivery> claimDue(Instant now, int limit, Duration lease);

    boolean reschedule(String taskId, String leaseToken, Instant nextAt, String error);

    void acknowledge(String taskId, Instant now);
}
