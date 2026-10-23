package com.codemind.review.application;

import com.codemind.common.event.ReviewCompleted;
import com.codemind.review.domain.publication.*;
import org.junit.jupiter.api.Test;

import java.time.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class ReviewOutboxDispatcherTest {
    private final Instant now = Instant.parse("2026-10-22T10:00:00Z");
    private final MemoryOutbox outbox = new MemoryOutbox();
    private final List<ReviewCompleted> sent = new ArrayList<>();

    @Test
    void dispatcherResendsSameEventUntilAcknowledged() {
        ReviewCompleted event = event();
        outbox.insert(event);
        ReviewOutboxDispatcher dispatcher = dispatcher();
        assertEquals(1, dispatcher.dispatchDue());
        assertEquals(List.of(event), sent);
        assertEquals(Instant.parse("2026-10-22T10:01:00Z"), outbox.next);
        outbox.eligible = true;
        assertEquals(1, dispatcher.dispatchDue());
        assertSame(sent.get(0), sent.get(1));
        outbox.acknowledge(event.taskId(), now);
        assertEquals(0, dispatcher.dispatchDue());
    }

    @Test
    void oneSendFailureDoesNotDropRest() {
        ReviewCompleted first = event();
        ReviewCompleted second = event();
        outbox.insert(first);
        outbox.insert(second);
        var dispatcher = new ReviewOutboxDispatcher(outbox, event -> {
            if (event.eventId().equals(first.eventId())) throw new IllegalStateException("MQ unavailable");
            sent.add((ReviewCompleted) event);
        }, Clock.fixed(now, ZoneOffset.UTC), 50, Duration.ofSeconds(120), Duration.ofSeconds(60));
        assertEquals(2, dispatcher.dispatchDue());
        assertEquals(List.of(second), sent);
        assertEquals(2, outbox.reschedules);
        assertNotNull(outbox.errors.get(first.taskId()));
    }

    @Test
    void dispatchTaskNeverSendsAnotherPendingTask() {
        ReviewCompleted wanted = event();
        ReviewCompleted other = event();
        outbox.insert(wanted);
        outbox.insert(other);
        assertTrue(dispatcher().dispatchTask(wanted.taskId()));
        assertEquals(List.of(wanted), sent);
        assertFalse(dispatcher().dispatchTask("unknown"));
    }

    @Test
    void acknowledgementDuringSendCannotBeReopenedByReschedule() {
        ReviewCompleted event = event();
        outbox.insert(event);
        var dispatcher = new ReviewOutboxDispatcher(outbox,
                ignored -> outbox.acknowledge(event.taskId(), now), Clock.fixed(now, ZoneOffset.UTC),
                50, Duration.ofSeconds(120), Duration.ofSeconds(60));
        assertEquals(1, dispatcher.dispatchDue());
        assertEquals(0, dispatcher.dispatchDue());
    }

    private ReviewOutboxDispatcher dispatcher() {
        return new ReviewOutboxDispatcher(outbox, e -> sent.add((ReviewCompleted) e),
                Clock.fixed(now, ZoneOffset.UTC), 50, Duration.ofSeconds(120), Duration.ofSeconds(60));
    }

    static ReviewCompleted event() {
        return ReviewCompleted.of(UUID.randomUUID().toString(), "owner/repo", 7, "head", "summary", List.of());
    }

    static final class MemoryOutbox implements ReviewOutboxRepository {
        final Map<String, ReviewCompleted> events = new LinkedHashMap<>();
        final Set<String> acknowledged = new HashSet<>();
        final Map<String, String> errors = new HashMap<>();
        boolean eligible = true;
        Instant next;
        int reschedules;

        public Optional<OutboxEntry> findByTaskId(String taskId) {
            ReviewCompleted event = events.get(taskId);
            return event == null ? Optional.empty() : Optional.of(new OutboxEntry(event,
                    event.occurredAt(), event.occurredAt(), 0, acknowledged.contains(taskId) ? event.occurredAt() : null));
        }
        public void insert(ReviewCompleted event) { events.putIfAbsent(event.taskId(), event); }
        public List<OutboxDelivery> claimDue(Instant now, int limit, Duration lease) {
            if (!eligible) return List.of();
            eligible = false;
            return events.values().stream().filter(e -> !acknowledged.contains(e.taskId()))
                    .limit(limit).map(e -> new OutboxDelivery(e, "lease")).toList();
        }
        public Optional<OutboxDelivery> claimTask(String taskId, Instant now, Duration lease) {
            return findByTaskId(taskId).filter(e -> e.acknowledgedAt() == null)
                    .map(e -> new OutboxDelivery(e.event(), "lease"));
        }
        public boolean reschedule(String taskId, String token, Instant nextAt, String error) {
            reschedules++;
            if (acknowledged.contains(taskId)) return false;
            next = nextAt;
            errors.put(taskId, error);
            return true;
        }
        public void acknowledge(String taskId, Instant at) { acknowledged.add(taskId); eligible = true; }
    }
}
