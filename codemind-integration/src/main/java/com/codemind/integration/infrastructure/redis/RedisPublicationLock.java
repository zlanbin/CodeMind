package com.codemind.integration.infrastructure.redis;

import com.codemind.integration.application.port.PublicationLease;
import com.codemind.integration.application.port.PublicationLock;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Redis-backed publication lock with token-checked renewal and release. */
@Component
public class RedisPublicationLock implements PublicationLock, AutoCloseable {

    static final String KEY_PREFIX = "codemind:publish-lock:";
    static final Duration DEFAULT_TTL = Duration.ofSeconds(120);
    static final Duration DEFAULT_RENEWAL_INTERVAL = Duration.ofSeconds(30);

    private static final RedisScript<Long> RENEW_SCRIPT = new DefaultRedisScript<>("""
            if redis.call('get', KEYS[1]) == ARGV[1] then
                return redis.call('pexpire', KEYS[1], ARGV[2])
            end
            return 0
            """, Long.class);

    private static final RedisScript<Long> RELEASE_SCRIPT = new DefaultRedisScript<>("""
            if redis.call('get', KEYS[1]) == ARGV[1] then
                return redis.call('del', KEYS[1])
            end
            return 0
            """, Long.class);

    private final StringRedisTemplate redisTemplate;
    private final Duration ttl;
    private final Duration renewalInterval;
    private final ScheduledExecutorService scheduler;
    private final Set<Lease> leases = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean closed = new AtomicBoolean();

    @Autowired
    public RedisPublicationLock(StringRedisTemplate redisTemplate) {
        this(redisTemplate, DEFAULT_TTL, DEFAULT_RENEWAL_INTERVAL,
                Executors.newSingleThreadScheduledExecutor(runnable -> {
                    Thread thread = new Thread(runnable, "publication-lock-renewer");
                    thread.setDaemon(true);
                    return thread;
                }));
    }

    /** The lock owns the supplied scheduler and shuts it down on close. */
    public RedisPublicationLock(StringRedisTemplate redisTemplate,
                                Duration ttl,
                                Duration renewalInterval,
                                ScheduledExecutorService scheduler) {
        this.redisTemplate = redisTemplate;
        this.ttl = ttl;
        this.renewalInterval = renewalInterval;
        this.scheduler = scheduler;
    }

    @Override
    public Optional<PublicationLease> tryAcquire(String taskId) {
        if (closed.get()) {
            return Optional.empty();
        }
        String key = KEY_PREFIX + taskId;
        String token = UUID.randomUUID().toString();
        try {
            if (!Boolean.TRUE.equals(redisTemplate.opsForValue().setIfAbsent(key, token, ttl))) {
                return Optional.empty();
            }
        } catch (RuntimeException exception) {
            return Optional.empty();
        }
        Lease lease = new Lease(key, token);
        leases.add(lease);
        try {
            lease.scheduleRenewal();
            if (closed.get()) {
                lease.close();
                return Optional.empty();
            }
            return Optional.of(lease);
        } catch (RejectedExecutionException exception) {
            lease.close();
            return Optional.empty();
        }
    }

    private void release(String key, String token) {
        try {
            redisTemplate.execute(RELEASE_SCRIPT, List.of(key), token);
        } catch (RuntimeException ignored) {
            // The local lease is already closed; Redis remains protected by its TTL.
        }
    }

    private final class Lease implements PublicationLease {
        private final String key;
        private final String token;
        private final AtomicBoolean held = new AtomicBoolean(true);
        private final AtomicBoolean closedLease = new AtomicBoolean();
        private volatile ScheduledFuture<?> renewal;

        private Lease(String key, String token) {
            this.key = key;
            this.token = token;
        }

        private void scheduleRenewal() {
            long delayMillis = renewalInterval.toMillis();
            renewal = scheduler.scheduleWithFixedDelay(this::renew,
                    delayMillis, delayMillis, TimeUnit.MILLISECONDS);
            if (!held.get() || closedLease.get()) {
                renewal.cancel(false);
            }
        }

        private void renew() {
            if (!held.get() || closedLease.get()) {
                return;
            }
            try {
                Long result = redisTemplate.execute(RENEW_SCRIPT, List.of(key), token,
                        Long.toString(ttl.toMillis()));
                if (!Long.valueOf(1L).equals(result)) {
                    invalidate();
                }
            } catch (RuntimeException exception) {
                invalidate();
            }
        }

        private void invalidate() {
            if (held.compareAndSet(true, false)) {
                ScheduledFuture<?> future = renewal;
                if (future != null) {
                    future.cancel(false);
                }
                leases.remove(this);
            }
        }

        @Override
        public boolean isHeld() {
            if (!held.get() || closedLease.get() || closed.get()) {
                return false;
            }
            try {
                if (!token.equals(redisTemplate.opsForValue().get(key))) {
                    invalidate();
                    return false;
                }
                return held.get() && !closedLease.get() && !closed.get();
            } catch (RuntimeException exception) {
                invalidate();
                return false;
            }
        }

        @Override
        public void close() {
            if (!closedLease.compareAndSet(false, true)) {
                return;
            }
            held.set(false);
            ScheduledFuture<?> future = renewal;
            if (future != null) {
                future.cancel(false);
            }
            leases.remove(this);
            release(key, token);
        }
    }

    @Override
    @PreDestroy
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        for (Lease lease : leases.toArray(Lease[]::new)) {
            lease.close();
        }
        scheduler.shutdownNow();
    }
}
