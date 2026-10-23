package com.codemind.integration.infrastructure.redis;

import com.codemind.integration.application.port.PublicationLease;
import com.codemind.integration.application.port.PublicationLock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** External Redis and time only are mocked; every assertion exercises the real lock/lease. */
class RedisPublicationLockTest {

    private static final String KEY = "codemind:publish-lock:task-4-unit";
    private static final Duration TTL = Duration.ofSeconds(120);
    private static final Duration INTERVAL = Duration.ofSeconds(30);

    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    private final ValueOperations<String, String> values = mock(ValueOperations.class);
    private final ScheduledExecutorService scheduler = mock(ScheduledExecutorService.class);
    private final ScheduledFuture<?> renewal = mock(ScheduledFuture.class);
    private final List<Runnable> renewals = new ArrayList<>();
    private RedisPublicationLock lock;
    private String token;

    @BeforeEach
    void setUp() {
        when(redis.opsForValue()).thenReturn(values);
        when(values.setIfAbsent(eq(KEY), anyString(), eq(TTL))).thenAnswer(call -> {
            token = call.getArgument(1);
            return true;
        });
        when(values.get(KEY)).thenAnswer(call -> token);
        when(redis.execute(org.mockito.ArgumentMatchers.<RedisScript<Long>>any(),
                anyList(), any(Object[].class))).thenReturn(1L);
        doAnswer(call -> {
            renewals.add(call.getArgument(0));
            return renewal;
        }).when(scheduler).scheduleWithFixedDelay(any(Runnable.class), anyLong(), anyLong(), eq(MILLISECONDS));
        lock = new RedisPublicationLock(redis, TTL, INTERVAL, scheduler);
    }

    @Test
    void busyLockDoesNotScheduleRenewal() {
        when(values.setIfAbsent(eq(KEY), anyString(), eq(TTL))).thenReturn(false);
        assertThat(lock.tryAcquire("task-4-unit")).isEmpty();
        verifyNoInteractions(scheduler);
    }

    @Test
    void unknownAcquisitionResultDoesNotScheduleRenewal() {
        when(values.setIfAbsent(eq(KEY), anyString(), eq(TTL))).thenReturn(null);
        assertThat(lock.tryAcquire("task-4-unit")).isEmpty();
        verifyNoInteractions(scheduler);
    }

    @Test
    void redisUnavailableDuringAcquireFailsClosed() {
        when(values.setIfAbsent(eq(KEY), anyString(), eq(TTL)))
                .thenThrow(new RedisConnectionFailureException("test Redis unavailable"));
        assertThat(lock.tryAcquire("task-4-unit")).isEmpty();
        verifyNoInteractions(scheduler);
    }

    @Test
    void acquiredLeaseUsesUuidTokenAndSchedulesAtRenewalInterval() {
        PublicationLease lease = acquire();
        assertThat(UUID.fromString(token).toString()).isEqualTo(token);
        assertThat(lease.isHeld()).isTrue();
        verify(scheduler).scheduleWithFixedDelay(any(Runnable.class), eq(30_000L), eq(30_000L), eq(MILLISECONDS));
    }

    @Test
    void renewalSendsSameTokenAndTtlToAtomicRedisScript() {
        PublicationLease lease = acquire();
        renewals.getFirst().run();
        assertThat(lease.isHeld()).isTrue();
        verify(redis).execute(org.mockito.ArgumentMatchers.<RedisScript<Long>>any(),
                eq(List.of(KEY)), eq(token), eq("120000"));
    }

    @Test
    void failedRenewalPermanentlyInvalidatesLeaseAndStopsRenewing() {
        PublicationLease lease = acquire();
        when(redis.execute(org.mockito.ArgumentMatchers.<RedisScript<Long>>any(),
                anyList(), any(Object[].class))).thenReturn(0L, 1L);
        renewals.getFirst().run();
        assertThat(lease.isHeld()).isFalse();
        renewals.getFirst().run();
        assertThat(lease.isHeld()).isFalse();
        verify(renewal).cancel(false);
        verify(redis, times(1)).execute(org.mockito.ArgumentMatchers.<RedisScript<Long>>any(),
                anyList(), any(Object[].class));
        verify(values, never()).get(KEY);
    }

    @Test
    void unknownRenewalResultPermanentlyInvalidatesLease() {
        PublicationLease lease = acquire();
        when(redis.execute(org.mockito.ArgumentMatchers.<RedisScript<Long>>any(),
                anyList(), any(Object[].class))).thenReturn(null);
        renewals.getFirst().run();
        assertThat(lease.isHeld()).isFalse();
    }

    @Test
    void renewalExceptionPermanentlyInvalidatesLease() {
        PublicationLease lease = acquire();
        when(redis.execute(org.mockito.ArgumentMatchers.<RedisScript<Long>>any(),
                anyList(), any(Object[].class)))
                .thenThrow(new RedisConnectionFailureException("test Redis unavailable"));
        renewals.getFirst().run();
        assertThat(lease.isHeld()).isFalse();
        verify(renewal).cancel(false);
    }

    @Test
    void isHeldChecksCurrentRedisTokenSynchronouslyAndCannotRecover() {
        PublicationLease lease = acquire();
        assertThat(lease.isHeld()).isTrue();
        when(values.get(KEY)).thenReturn("new-owner", token);
        assertThat(lease.isHeld()).isFalse();
        assertThat(lease.isHeld()).isFalse();
        verify(values, times(2)).get(KEY);
        verify(renewal).cancel(false);
    }

    @Test
    void missingKeyInvalidatesLeaseBeforeNextRenewal() {
        PublicationLease lease = acquire();
        when(values.get(KEY)).thenReturn(null);
        assertThat(lease.isHeld()).isFalse();
        renewals.getFirst().run();
        verify(redis, never()).execute(org.mockito.ArgumentMatchers.<RedisScript<Long>>any(),
                anyList(), any(Object[].class));
    }

    @Test
    void tokenCheckExceptionFailsClosedAndCannotRecover() {
        PublicationLease lease = acquire();
        when(values.get(KEY)).thenThrow(new RedisConnectionFailureException("test Redis unavailable"));
        assertThat(lease.isHeld()).isFalse();
        doReturn(token).when(values).get(KEY);
        assertThat(lease.isHeld()).isFalse();
        verify(values, times(1)).get(KEY);
    }

    @Test
    void closeDuringTokenCheckCannotReturnHeldFromStaleReply() {
        PublicationLease lease = acquire();
        when(values.get(KEY)).thenAnswer(call -> {
            lease.close();
            return token;
        });
        assertThat(lease.isHeld()).isFalse();
    }

    @Test
    void renewalFailureDuringTokenCheckCannotReturnHeldFromStaleReply() {
        PublicationLease lease = acquire();
        when(redis.execute(org.mockito.ArgumentMatchers.<RedisScript<Long>>any(),
                anyList(), any(Object[].class))).thenReturn(0L);
        when(values.get(KEY)).thenAnswer(call -> {
            renewals.getFirst().run();
            return token;
        });
        assertThat(lease.isHeld()).isFalse();
    }

    @Test
    void closeReleasesOnlyItsTokenOnceAndStopsRenewal() {
        PublicationLease lease = acquire();
        lease.close();
        lease.close();
        renewals.getFirst().run();
        assertThat(lease.isHeld()).isFalse();
        verify(renewal).cancel(false);
        verify(redis, times(1)).execute(org.mockito.ArgumentMatchers.<RedisScript<Long>>any(),
                eq(List.of(KEY)), eq(token));
        verify(values, never()).get(KEY);
        verify(redis, never()).delete(anyString());
    }

    @Test
    void lostLeaseStillUsesCompareAndDeleteOnClose() {
        PublicationLease lease = acquire();
        when(values.get(KEY)).thenReturn("new-owner");
        assertThat(lease.isHeld()).isFalse();
        lease.close();
        verify(redis).execute(org.mockito.ArgumentMatchers.<RedisScript<Long>>any(), eq(List.of(KEY)), eq(token));
    }

    @Test
    void releaseExceptionDoesNotLeaveLeaseUsableOrRepeatRelease() {
        PublicationLease lease = acquire();
        when(redis.execute(org.mockito.ArgumentMatchers.<RedisScript<Long>>any(),
                anyList(), any(Object[].class)))
                .thenThrow(new RedisConnectionFailureException("test Redis unavailable"));
        lease.close();
        lease.close();
        assertThat(lease.isHeld()).isFalse();
        verify(redis, times(1)).execute(org.mockito.ArgumentMatchers.<RedisScript<Long>>any(),
                anyList(), any(Object[].class));
    }

    @Test
    void schedulerRejectionReleasesAcquiredTokenAndReturnsNoLease() {
        doThrow(new RejectedExecutionException("test scheduler stopped"))
                .when(scheduler).scheduleWithFixedDelay(any(Runnable.class), anyLong(), anyLong(), eq(MILLISECONDS));
        assertThat(lock.tryAcquire("task-4-unit")).isEmpty();
        verify(redis).execute(org.mockito.ArgumentMatchers.<RedisScript<Long>>any(), eq(List.of(KEY)), eq(token));
    }

    @Test
    void closingLockInvalidatesLeasesShutsDownSchedulerAndRejectsNewAcquisition() {
        PublicationLease lease = acquire();
        lock.close();
        lock.close();
        assertThat(lease.isHeld()).isFalse();
        assertThat(lock.tryAcquire("task-4-unit")).isEmpty();
        verify(scheduler, times(1)).shutdownNow();
        verify(values, times(1)).setIfAbsent(eq(KEY), anyString(), eq(TTL));
        verify(redis, times(1)).execute(org.mockito.ArgumentMatchers.<RedisScript<Long>>any(), eq(List.of(KEY)), eq(token));
    }

    @Test
    void springBeanExposesApplicationPortAndClosesItsSchedulerWithContext() {
        new ApplicationContextRunner()
                .withBean(StringRedisTemplate.class, () -> redis)
                .withUserConfiguration(RedisPublicationLock.class)
                .run(context -> {
                    assertThat(context).hasNotFailed().hasSingleBean(PublicationLock.class);
                    RedisPublicationLock bean = context.getBean(RedisPublicationLock.class);
                    PublicationLease lease = bean.tryAcquire("task-4-unit").orElseThrow();
                    assertThat(lease.isHeld()).isTrue();
                    context.close();
                    assertThat(lease.isHeld()).isFalse();
                    assertThat(bean.tryAcquire("task-4-unit")).isEmpty();
                });
    }

    private PublicationLease acquire() {
        return lock.tryAcquire("task-4-unit").orElseThrow();
    }
}
