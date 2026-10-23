package com.codemind.integration.domain.feedback;

import com.codemind.common.event.FindingFeedbackReceived;

import java.time.Instant;
import java.util.List;

/** 反馈登记账本（M9 D8）。 */
public interface FindingFeedbackRepository {

    /**
     * 按 {@code (repo, feedbackType, sourceId)} 去重登记；已存在返回 false，不覆盖首次登记的事件。
     *
     * @param superseded true 时直接登记为 SUPERSEDED（被同一人的回复取代的 👎）：只占位去重，永不投递
     */
    boolean registerIfAbsent(FindingFeedbackReceived event, boolean superseded);

    default boolean registerIfAbsent(FindingFeedbackReceived event) {
        return registerIfAbsent(event, false);
    }

    /** 投递失败到这个次数转 FAILED，不再重投。 */
    int MAX_ATTEMPTS = 10;

    /**
     * 最早的 PENDING，最多 limit 条。payload 无法反序列化的行就地转 FAILED 并跳过，不让它挡住后面的事件。
     */
    List<FindingFeedbackReceived> findPending(int limit);

    /** 仅 PENDING 才转 SENT，返回是否转成。 */
    boolean markSent(String eventId, Instant now);

    /** 记一次失败；累计到 {@link #MAX_ATTEMPTS} 转 FAILED。 */
    void recordFailure(String eventId, String reason);

    /**
     * 轮询目标：发送账本里 {@code PUBLISHED} 且最近 since 之后有更新的 review（M9 D7）。
     * 读的是 M7b 的 {@code integration.review_publication}，只读。
     */
    List<PublishedReview> findPublishedSince(Instant since);

    record PublishedReview(String repo, int prNumber, long reviewId) {
    }
}
