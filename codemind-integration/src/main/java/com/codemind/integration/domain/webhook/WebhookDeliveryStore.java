package com.codemind.integration.domain.webhook;

/**
 * Webhook 投递幂等存储（出站端口）。GitHub 在超时或网络抖动时会重投同一个 deliveryId。
 */
public interface WebhookDeliveryStore {

    /**
     * 尝试把该投递标记为已处理，要求原子操作（存在即失败）。
     *
     * @param deliveryId Webhook 投递 ID
     * @return true 表示首次处理，false 表示此前已处理过（重放）
     */
    boolean markProcessed(String deliveryId);
}
