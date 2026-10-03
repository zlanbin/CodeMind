package com.codemind.integration.infrastructure.redis;

import com.codemind.integration.domain.webhook.WebhookDeliveryStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 基于 Redis {@code SET NX EX} 的幂等存储。
 *
 * <p>这是第一道闸，挡住 GitHub 重投；最终兜底是 review-service 的
 * {@code (repo, pr_number, head_sha)} 唯一索引。TTL 过期后重放会被唯一索引拦住。
 */
@Component
public class RedisWebhookDeliveryStore implements WebhookDeliveryStore {

    private static final String KEY_PREFIX = "webhook:";

    private final StringRedisTemplate redisTemplate;
    private final Duration ttl;

    public RedisWebhookDeliveryStore(StringRedisTemplate redisTemplate,
                                     @Value("${codemind.webhook.idempotency-ttl:P7D}") Duration ttl) {
        this.redisTemplate = redisTemplate;
        this.ttl = ttl;
    }

    @Override
    public boolean markProcessed(String deliveryId) {
        Boolean first = redisTemplate.opsForValue()
                .setIfAbsent(KEY_PREFIX + deliveryId, "1", ttl);
        return Boolean.TRUE.equals(first);
    }
}
