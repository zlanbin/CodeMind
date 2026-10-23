package com.codemind.review.application;

import com.codemind.review.domain.finding.TaskFingerprintException;
import com.codemind.review.domain.finding.TaskFingerprintPort;
import com.codemind.review.domain.publication.FingerprintRegistration;
import com.codemind.review.domain.publication.FingerprintRegistrationDelivery;
import com.codemind.review.domain.publication.FingerprintRegistrationOutboxRepository;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class FingerprintRegistrationDispatcherTest {

    private final Instant now = Instant.parse("2026-10-24T00:00:00Z");
    private final Clock clock = Clock.fixed(now, ZoneOffset.UTC);

    @Test
    void successfulRegistrationAcknowledges() {
        var registration = new FingerprintRegistration("task-1", "org/repo", 7, List.of("fp1"));
        var outbox = new StubOutbox(List.of(new FingerprintRegistrationDelivery(registration, "lease-1")));
        var port = new StubPort(null);
        var dispatcher = new FingerprintRegistrationDispatcher(outbox, port, clock, 50,
                Duration.ofSeconds(120), Duration.ofSeconds(60));

        int count = dispatcher.dispatchDue();

        assertEquals(1, count);
        assertEquals(List.of("task-1"), outbox.acknowledged);
        assertTrue(outbox.rescheduledTaskIds.isEmpty(), "成功不该重排");
        assertEquals(List.of("org/repo"), port.recordedRepos);
    }

    @Test
    void failedRegistrationReschedulesWithoutThrowing() {
        var registration = new FingerprintRegistration("task-2", "org/repo", 7, List.of("fp1"));
        var outbox = new StubOutbox(List.of(new FingerprintRegistrationDelivery(registration, "lease-2")));
        var port = new StubPort(new TaskFingerprintException("memory 不可达", null));
        var dispatcher = new FingerprintRegistrationDispatcher(outbox, port, clock, 50,
                Duration.ofSeconds(120), Duration.ofSeconds(60));

        int count = assertDoesNotThrow(dispatcher::dispatchDue, "单条失败不该让整批调用抛异常");

        assertEquals(1, count);
        assertTrue(outbox.acknowledged.isEmpty(), "失败不该确认");
        assertEquals(List.of("task-2"), outbox.rescheduledTaskIds);
    }

    private static final class StubOutbox implements FingerprintRegistrationOutboxRepository {
        private final List<FingerprintRegistrationDelivery> deliveries;
        private final List<String> acknowledged = new ArrayList<>();
        private final List<String> rescheduledTaskIds = new ArrayList<>();

        StubOutbox(List<FingerprintRegistrationDelivery> deliveries) {
            this.deliveries = deliveries;
        }

        @Override
        public void insert(String taskId, String repo, int prNumber, List<String> fingerprints) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<FingerprintRegistrationDelivery> claimDue(Instant now, int limit, Duration lease) {
            return deliveries;
        }

        @Override
        public boolean reschedule(String taskId, String leaseToken, Instant nextAt, String error) {
            rescheduledTaskIds.add(taskId);
            return true;
        }

        @Override
        public void acknowledge(String taskId, Instant now) {
            acknowledged.add(taskId);
        }
    }

    private static final class StubPort implements TaskFingerprintPort {
        private final TaskFingerprintException failure;
        private final List<String> recordedRepos = new ArrayList<>();

        StubPort(TaskFingerprintException failure) {
            this.failure = failure;
        }

        @Override
        public java.util.Set<String> published(String repo, int prNumber) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void record(String repo, int prNumber, List<String> fingerprints) {
            if (failure != null) {
                throw failure;
            }
            recordedRepos.add(repo);
        }
    }
}
