package com.codemind.integration.infrastructure.persistence;

import com.codemind.common.event.FindingFeedbackReceived;
import com.codemind.common.event.FindingFeedbackReceived.FeedbackType;
import com.codemind.common.event.FindingFeedbackReceived.FindingRef;
import com.codemind.integration.domain.feedback.FindingFeedbackRepository;
import com.codemind.integration.domain.feedback.FindingFeedbackRepository.PublishedReview;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.JdbcClientAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.JdbcTemplateAutoConfiguration;
import org.springframework.boot.autoconfigure.transaction.TransactionAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** 真实 PG，只碰本用例的 repo 行；不连 GitHub / MQ。 */
@SpringBootTest(classes = FindingFeedbackRepositoryIT.PersistenceConfiguration.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE)
class FindingFeedbackRepositoryIT {

    @Configuration(proxyBeanMethods = false)
    @ImportAutoConfiguration({DataSourceAutoConfiguration.class, JdbcTemplateAutoConfiguration.class,
            JdbcClientAutoConfiguration.class, DataSourceTransactionManagerAutoConfiguration.class,
            TransactionAutoConfiguration.class, FlywayAutoConfiguration.class, JacksonAutoConfiguration.class})
    @Import(JdbcFindingFeedbackRepository.class)
    static class PersistenceConfiguration {
    }

    private static final String FP = "b".repeat(64);

    @Autowired
    FindingFeedbackRepository repository;
    @Autowired
    JdbcClient jdbc;

    private final String repo = "codemind/feedback-it-" + UUID.randomUUID();
    private final String taskId = "feedback-it-" + UUID.randomUUID();

    @AfterEach
    void cleanUp() {
        jdbc.sql("delete from integration.finding_feedback where repo = :r").param("r", repo).update();
        jdbc.sql("delete from integration.review_publication where task_id = :t").param("t", taskId).update();
    }

    @Test
    void sameSourceRegistersOnceAndKeepsFirstEvent() {
        FindingFeedbackReceived first = event(FeedbackType.THUMBS_DOWN, 900L, null);
        FindingFeedbackReceived replayed = event(FeedbackType.THUMBS_DOWN, 900L, null);
        FindingFeedbackReceived reply = event(FeedbackType.REPLY, 900L, "不适用，因为这是 DTO");

        assertThat(repository.registerIfAbsent(first)).isTrue();
        assertThat(repository.registerIfAbsent(replayed)).isFalse();
        assertThat(repository.registerIfAbsent(reply)).isTrue();  // 同一 id 但来源类型不同

        List<FindingFeedbackReceived> pending = pendingOfThisRepo();
        assertThat(pending).extracting(FindingFeedbackReceived::eventId)
                .containsExactlyInAnyOrder(first.eventId(), reply.eventId());
        FindingFeedbackReceived roundTripped = pending.stream()
                .filter(e -> e.eventId().equals(reply.eventId())).findFirst().orElseThrow();
        assertThat(roundTripped).isEqualTo(reply);
    }

    @Test
    void supersededThumbsDownOccupiesTheSourceButIsNeverPending() {
        FindingFeedbackReceived down = event(FeedbackType.THUMBS_DOWN, 906L, null);

        assertThat(repository.registerIfAbsent(down, true)).isTrue();
        assertThat(repository.registerIfAbsent(event(FeedbackType.THUMBS_DOWN, 906L, null))).isFalse();
        assertThat(pendingOfThisRepo()).isEmpty();
        assertThat(jdbc.sql("select state from integration.finding_feedback where event_id = :id")
                .param("id", down.eventId()).query(String.class).single()).isEqualTo("SUPERSEDED");
        assertThat(repository.markSent(down.eventId(), Instant.now())).isFalse();
    }

    @Test
    void sentLeavesPendingAndFailureKeepsItPending() {
        FindingFeedbackReceived a = event(FeedbackType.THUMBS_DOWN, 901L, null);
        FindingFeedbackReceived b = event(FeedbackType.THUMBS_DOWN, 902L, null);
        repository.registerIfAbsent(a);
        repository.registerIfAbsent(b);

        assertThat(repository.markSent(a.eventId(), Instant.now())).isTrue();
        assertThat(repository.markSent(a.eventId(), Instant.now())).isFalse();
        repository.recordFailure(b.eventId(), "x".repeat(2000));

        assertThat(pendingOfThisRepo()).extracting(FindingFeedbackReceived::eventId).containsExactly(b.eventId());
        assertThat(jdbc.sql("select length(last_error) from integration.finding_feedback where event_id = :id")
                .param("id", b.eventId()).query(Integer.class).single()).isEqualTo(500);
    }

    @Test
    void poisonedPayloadIsFailedAndDoesNotBlockTheRest() {
        FindingFeedbackReceived good = event(FeedbackType.THUMBS_DOWN, 903L, null);
        FindingFeedbackReceived bad = event(FeedbackType.THUMBS_DOWN, 904L, null);
        repository.registerIfAbsent(bad);
        repository.registerIfAbsent(good);
        jdbc.sql("update integration.finding_feedback set event_payload = '{\"findings\": []}'::jsonb where event_id = :id")
                .param("id", bad.eventId()).update();

        assertThat(pendingOfThisRepo()).extracting(FindingFeedbackReceived::eventId).containsExactly(good.eventId());
        assertThat(state(bad.eventId())).isEqualTo("FAILED");
    }

    @Test
    void repeatedFailuresEventuallyStopRetrying() {
        FindingFeedbackReceived e = event(FeedbackType.THUMBS_DOWN, 905L, null);
        repository.registerIfAbsent(e);

        for (int i = 1; i < FindingFeedbackRepository.MAX_ATTEMPTS; i++) {
            repository.recordFailure(e.eventId(), "broker down");
        }
        assertThat(state(e.eventId())).isEqualTo("PENDING");
        repository.recordFailure(e.eventId(), "broker down");
        assertThat(state(e.eventId())).isEqualTo("FAILED");
        assertThat(repository.markSent(e.eventId(), Instant.now())).isFalse();
    }

    private String state(String eventId) {
        return jdbc.sql("select state from integration.finding_feedback where event_id = :id")
                .param("id", eventId).query(String.class).single();
    }

    @Test
    void findsOnlyRecentlyPublishedReviews() {
        jdbc.sql("""
                insert into integration.review_publication
                    (task_id, event_payload, content_digest, repo, pr_number, head_sha, state, mode,
                     review_id, inline_count, degraded, updated_at)
                values (:t, '{}'::jsonb, :d, :r, 3, 'h', 'PUBLISHED', 'INLINE', 777, 1, false, now())
                """).param("t", taskId).param("d", "0".repeat(64)).param("r", repo).update();

        assertThat(repository.findPublishedSince(Instant.now().minus(Duration.ofDays(1))))
                .contains(new PublishedReview(repo, 3, 777));
        assertThat(repository.findPublishedSince(Instant.now().plus(Duration.ofDays(1))))
                .doesNotContain(new PublishedReview(repo, 3, 777));
    }

    private List<FindingFeedbackReceived> pendingOfThisRepo() {
        return repository.findPending(1000).stream().filter(e -> e.repo().equals(repo)).toList();
    }

    private FindingFeedbackReceived event(FeedbackType type, long sourceId, String reply) {
        return FindingFeedbackReceived.of(repo, 3, 55L, type, sourceId, "lan-bin", reply, "意见正文",
                List.of(new FindingRef(FP, "CORRECTNESS")));
    }
}
