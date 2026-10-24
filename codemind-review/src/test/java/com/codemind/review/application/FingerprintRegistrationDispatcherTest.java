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
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class FingerprintRegistrationDispatcherTest {

    private final Instant now = Instant.parse("2026-10-24T00:00:00Z");
    private final Clock clock = Clock.fixed(now, ZoneOffset.UTC);

    @Test
    void successfulRegistrationAcknowledges() {
        var registration = new FingerprintRegistration("task-1", "org/repo", 7, List.of("fp1"));
        var outbox = new StubOutbox(List.of(new FingerprintRegistrationDelivery(registration, "lease-1")));
        var port = new StubPort(Map.of());
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
        var port = new StubPort(Map.of(7, new TaskFingerprintException("memory 不可达", null)));
        var dispatcher = new FingerprintRegistrationDispatcher(outbox, port, clock, 50,
                Duration.ofSeconds(120), Duration.ofSeconds(60));

        int count = assertDoesNotThrow(dispatcher::dispatchDue, "单条失败不该让整批调用抛异常");

        assertEquals(1, count);
        assertTrue(outbox.acknowledged.isEmpty(), "失败不该确认");
        assertEquals(List.of("task-2"), outbox.rescheduledTaskIds);
    }

    /** M10 Final Review F4：一条失败不能挡住同批其它任务，异常网要兜住任意 RuntimeException。 */
    @Test
    void oneFailureInBatchDoesNotBlockOthers() {
        var failing = new FingerprintRegistration("task-fail", "org/repo", 7, List.of("fp1"));
        var ok = new FingerprintRegistration("task-ok", "org/repo", 8, List.of("fp2"));
        var outbox = new StubOutbox(List.of(
                new FingerprintRegistrationDelivery(failing, "lease-fail"),
                new FingerprintRegistrationDelivery(ok, "lease-ok")));
        var port = new StubPort(Map.of(7, new IllegalStateException("意外运行时异常")));
        var dispatcher = new FingerprintRegistrationDispatcher(outbox, port, clock, 50,
                Duration.ofSeconds(120), Duration.ofSeconds(60));

        int count = assertDoesNotThrow(dispatcher::dispatchDue, "批内一条非 TaskFingerprintException 失败不该挡住其它任务");

        assertEquals(2, count);
        assertEquals(List.of("task-ok"), outbox.acknowledged, "失败的那条不该被确认");
        assertEquals(List.of("task-fail"), outbox.rescheduledTaskIds, "失败的那条应被重排");
        assertEquals(List.of("org/repo"), port.recordedRepos, "正常那条应照常登记");
    }

    /** acknowledge 本身抛异常（如瞬时 DB 故障）也不该挡住批内其它任务。 */
    @Test
    void acknowledgeFailureDoesNotBlockOthers() {
        var ackThrows = new FingerprintRegistration("task-ack-fail", "org/repo", 7, List.of("fp1"));
        var ok = new FingerprintRegistration("task-ok", "org/repo", 8, List.of("fp2"));
        var outbox = new StubOutbox(List.of(
                new FingerprintRegistrationDelivery(ackThrows, "lease-1"),
                new FingerprintRegistrationDelivery(ok, "lease-2")));
        outbox.acknowledgeFailuresFor.add("task-ack-fail");
        var port = new StubPort(Map.of());
        var dispatcher = new FingerprintRegistrationDispatcher(outbox, port, clock, 50,
                Duration.ofSeconds(120), Duration.ofSeconds(60));

        int count = assertDoesNotThrow(dispatcher::dispatchDue, "acknowledge 抛异常不该挡住批内其它任务");

        assertEquals(2, count);
        assertEquals(List.of("task-ok"), outbox.acknowledged);
        assertEquals(List.of("org/repo", "org/repo"), port.recordedRepos, "两条都该照常调用 record");
    }

    private static final class StubOutbox implements FingerprintRegistrationOutboxRepository {
        private final List<FingerprintRegistrationDelivery> deliveries;
        private final List<String> acknowledged = new ArrayList<>();
        private final List<String> rescheduledTaskIds = new ArrayList<>();
        private final java.util.Set<String> acknowledgeFailuresFor = new java.util.HashSet<>();

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
            if (acknowledgeFailuresFor.contains(taskId)) {
                throw new IllegalStateException("模拟 acknowledge 瞬时失败");
            }
            acknowledged.add(taskId);
        }
    }

    private static final class StubPort implements TaskFingerprintPort {
        private final Map<Integer, RuntimeException> failures;
        private final List<String> recordedRepos = new ArrayList<>();

        StubPort(Map<Integer, RuntimeException> failures) {
            this.failures = failures;
        }

        @Override
        public java.util.Set<String> published(String repo, int prNumber) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void record(String repo, int prNumber, List<String> fingerprints) {
            RuntimeException failure = failures.get(prNumber);
            if (failure != null) {
                throw failure;
            }
            recordedRepos.add(repo);
        }
    }
}
