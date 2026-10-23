package com.codemind.integration.infrastructure.redis;

import com.codemind.integration.application.port.PublicationLease;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicReference;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real compose Redis only: no Boot application, MQ consumer, SCM or LLM is started. */
class RedisPublicationLockIT {

    private static final Duration TTL = Duration.ofSeconds(2);
    private final String taskId = "it-publication-lock-" + UUID.randomUUID();
    private final String key = "codemind:publish-lock:" + taskId;
    private LettuceConnectionFactory connections;
    private StringRedisTemplate redis;
    private RedisPublicationLock lock;

    @BeforeEach
    void connect() {
        connections = new LettuceConnectionFactory("127.0.0.1", 6379);
        connections.afterPropertiesSet();
        redis = new StringRedisTemplate(connections);
        try (var connection = connections.getConnection()) {
            assertThat(connection.ping()).isEqualTo("PONG");
        }
    }

    @AfterEach
    void cleanUp() {
        try {
            if (lock != null) {
                lock.close();
            }
            if (redis != null) {
                redis.delete(key);
            }
        } finally {
            if (connections != null) {
                connections.destroy();
            }
        }
    }

    @Test
    void renewalKeepsOriginalTokenBeyondItsInitialTtl() {
        lock = new RedisPublicationLock(redis, TTL, Duration.ofMillis(300),
                Executors.newSingleThreadScheduledExecutor());
        try (PublicationLease lease = lock.tryAcquire(taskId).orElseThrow()) {
            String token = redis.opsForValue().get(key);
            assertThat(UUID.fromString(token).toString()).isEqualTo(token);
            assertThat(lock.tryAcquire(taskId)).isEmpty();
            // A missing PEXPIRE fails this sustained observation after the original 2s TTL.
            await().pollInterval(Duration.ofMillis(100)).during(Duration.ofSeconds(5))
                    .atMost(Duration.ofSeconds(8)).untilAsserted(() -> {
                        assertThat(redis.opsForValue().get(key)).isEqualTo(token);
                        assertThat(lease.isHeld()).isTrue();
                    });
        }
        assertThat(redis.hasKey(key)).isFalse();
    }

    @Test
    void renewalWithOldTokenCannotExtendOrDeleteReplacementLock() {
        AtomicReference<Runnable> renewal = new AtomicReference<>();
        ScheduledExecutorService scheduler = mock(ScheduledExecutorService.class);
        ScheduledFuture<?> future = mock(ScheduledFuture.class);
        doAnswer(call -> {
            renewal.set(call.getArgument(0));
            return future;
        }).when(scheduler).scheduleWithFixedDelay(any(Runnable.class), anyLong(), anyLong(), eq(MILLISECONDS));
        lock = new RedisPublicationLock(redis, Duration.ofSeconds(10), Duration.ofSeconds(1), scheduler);
        try (PublicationLease oldLease = lock.tryAcquire(taskId).orElseThrow()) {
            String replacementToken = UUID.randomUUID().toString();
            redis.opsForValue().set(key, replacementToken, TTL);
            renewal.get().run(); // Execute the real compare-and-PEXPIRE Lua against Redis.
            assertThat(oldLease.isHeld()).isFalse();
            oldLease.close(); // Execute compare-and-delete; it must not remove the new owner.
            assertThat(redis.opsForValue().get(key)).isEqualTo(replacementToken);
            assertThat(redis.getExpire(key, MILLISECONDS)).isPositive().isLessThanOrEqualTo(2000L);
            // Unconditional renewal would have extended the replacement to the old 10s TTL.
            await().atMost(Duration.ofSeconds(5)).untilAsserted(
                    () -> assertThat(redis.hasKey(key)).isFalse());
        }
    }

    @Test
    void stoppingRenewalAllowsExpiryAndAcquisitionWithNewToken() {
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        lock = new RedisPublicationLock(redis, TTL, Duration.ofMillis(300), scheduler);
        try (PublicationLease oldLease = lock.tryAcquire(taskId).orElseThrow()) {
            String oldToken = redis.opsForValue().get(key);
            scheduler.shutdownNow(); // Simulate renewer stopping, without releasing the key.
            assertThat(redis.opsForValue().get(key)).isEqualTo(oldToken);
            await().atMost(Duration.ofSeconds(5)).untilAsserted(
                    () -> assertThat(redis.hasKey(key)).isFalse());
            try (RedisPublicationLock replacementLock = new RedisPublicationLock(
                    redis, TTL, Duration.ofMillis(300), Executors.newSingleThreadScheduledExecutor());
                 PublicationLease replacement = replacementLock.tryAcquire(taskId).orElseThrow()) {
                assertThat(redis.opsForValue().get(key)).isNotEqualTo(oldToken);
                assertThat(replacement.isHeld()).isTrue();
                oldLease.close();
                assertThat(replacement.isHeld()).isTrue();
            }
        }
    }
}
