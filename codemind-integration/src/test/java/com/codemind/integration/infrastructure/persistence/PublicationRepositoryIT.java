package com.codemind.integration.infrastructure.persistence;

import com.codemind.common.event.ReviewCompleted;
import com.codemind.common.event.ReviewCompleted.LineComment;
import com.codemind.integration.domain.publication.PublicationMode;
import com.codemind.integration.domain.publication.PublicationRecord;
import com.codemind.integration.domain.publication.PublicationRepository;
import com.codemind.integration.domain.publication.PublicationResult;
import com.codemind.integration.domain.publication.PublicationState;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.flyway.FlywayMigrationStrategy;
import org.springframework.context.annotation.Bean;
import org.springframework.beans.factory.annotation.Value;
import java.nio.file.Files;
import java.nio.file.Path;
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
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Real compose PostgreSQL; no SCM, LLM, MQ, or Redis calls. Only this test's rows are removed. */
@SpringBootTest(classes = PublicationRepositoryIT.PersistenceConfiguration.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE)
class PublicationRepositoryIT {

    @Configuration(proxyBeanMethods = false)
    @ImportAutoConfiguration({DataSourceAutoConfiguration.class, JdbcTemplateAutoConfiguration.class,
            JdbcClientAutoConfiguration.class, DataSourceTransactionManagerAutoConfiguration.class,
            TransactionAutoConfiguration.class,
            FlywayAutoConfiguration.class, JacksonAutoConfiguration.class})
    @Import(JdbcPublicationRepository.class)
    static class PersistenceConfiguration {

        /** Test-only preparation: use the owning module's SQL, never add it to integration's locations. */
        @Bean
        ReviewHistoryPreparation reviewHistoryPreparation(
                @Value("${review.migrations.directory:../codemind-review/src/main/resources/db/migration}") String directory) {
            return new ReviewHistoryPreparation(Path.of(directory).toAbsolutePath().normalize());
        }

        @Bean
        FlywayMigrationStrategy prepareReviewThenMigrateIntegration(ReviewHistoryPreparation preparation) {
            return integration -> {
                preparation.prepare(integration.getConfiguration().getDataSource());
                integration.migrate();
                preparation.assertUntouched();
            };
        }
    }

    /** Fails if source migrations/history mismatch; deliberately no baseline, repair, clean, or skip. */
    static final class ReviewHistoryPreparation {
        private final Path directory;
        private JdbcClient reviewJdbc;
        private Flyway reviewFlyway;
        private List<String> preparedHistory;

        ReviewHistoryPreparation(Path directory) {
            this.directory = directory;
        }

        void prepare(DataSource dataSource) {
            if (!Files.isDirectory(directory)) {
                throw new IllegalStateException("Review migration directory missing: " + directory);
            }
            reviewJdbc = JdbcClient.create(dataSource);
            reviewFlyway = Flyway.configure().dataSource(dataSource)
                    .defaultSchema("public").schemas("public")
                    .locations("filesystem:" + directory.toString().replace('\\', '/'))
                    .baselineOnMigrate(false).load();
            reviewFlyway.migrate();
            preparedHistory = history();
            assertThat(reviewJdbc.sql("select version from public.flyway_schema_history where success order by installed_rank")
                    .query(String.class).list()).startsWith("1", "2", "3", "4");
        }

        void assertUntouched() {
            assertThat(history()).isEqualTo(preparedHistory);
            assertThat(reviewFlyway.validateWithResult().validationSuccessful).isTrue();
            assertThat(reviewFlyway.migrate().migrationsExecuted).isZero();
            assertThat(history()).isEqualTo(preparedHistory);
        }

        private List<String> history() {
            // Whole rows include checksums, ranks, timestamps, scripts, users, and success flags.
            return reviewJdbc.sql("select row_to_json(h)::text from public.flyway_schema_history h order by installed_rank")
                    .query(String.class).list();
        }
    }

    @Autowired PublicationRepository repository;
    @Autowired JdbcClient jdbc;
    @Autowired DataSource dataSource;
    @Autowired ObjectMapper mapper;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired Flyway flyway;
    @Autowired ReviewHistoryPreparation reviewHistory;

    private final String taskId = "publication-it-" + UUID.randomUUID();

    @AfterEach
    void cleanUp() {
        jdbc.sql("delete from integration.review_publication where task_id = :id")
                .param("id", taskId).update();
    }

    // Catches scanning public migrations or sharing the review service's Flyway history.
    @Test
    void integrationMigrationHasItsOwnSchemaAndHistory() {
        assertThat(jdbc.sql("select to_regclass('integration.review_publication')::text")
                .query(String.class).single()).isEqualTo("integration.review_publication");
        assertThat(jdbc.sql("select version from integration.flyway_schema_history where version is not null order by installed_rank")
                .query(String.class).list()).containsExactly("1", "2", "3");
        assertThat(jdbc.sql("select script from integration.flyway_schema_history where version is not null order by installed_rank")
                .query(String.class).list()).containsExactly("V1__create_review_publication.sql",
                        "V2__require_nonnegative_inline_count.sql", "V3__create_finding_feedback.sql");
        assertThat(jdbc.sql("select version from public.flyway_schema_history where success order by installed_rank")
                .query(String.class).list()).startsWith("1", "2", "3", "4");
        assertThat(jdbc.sql("select count(*) from public.flyway_schema_history where script = 'V1__create_review_publication.sql'")
                .query(Integer.class).single()).isZero();
        assertThat(flyway.getConfiguration().getDefaultSchema()).isEqualTo("integration");
        assertThat(flyway.getConfiguration().getSchemas()).containsExactly("integration");
        assertThat(flyway.getConfiguration().isBaselineOnMigrate()).isFalse();
        assertThat(flyway.validateWithResult().validationSuccessful).isTrue();
        assertThat(flyway.migrate().migrationsExecuted).isZero();
        reviewHistory.assertUntouched();
    }

    // Catches overwriting the original event or including transport metadata in the digest.
    @Test
    void replayKeepsTheFirstEventEvenWithNewTransportMetadata() throws Exception {
        ReviewCompleted first = event("original summary");
        PublicationRecord registered = repository.register(first);
        ReviewCompleted replay = new ReviewCompleted("another-delivery", Instant.parse("2026-10-22T09:10:11Z"),
                taskId, first.repo(), 7, first.headSha(), first.summaryBody(), first.comments());
        PublicationRecord repeated = repository.register(replay);

        assertThat(repeated).isEqualTo(registered);
        assertThat(repeated.event()).isEqualTo(first);
        assertThat(repeated.state()).isEqualTo(PublicationState.READY);
        assertThat(repeated.mode()).isEqualTo(PublicationMode.INLINE);
        assertThat(repeated.attemptToken()).isNull();
        assertThat(repeated.result()).isNull();
        assertThat(repeated.contentDigest()).matches("[0-9a-f]{64}");
        assertThat(repeated.createdAt()).isEqualTo(repeated.updatedAt());
        String json = jdbc.sql("select event_payload::text from integration.review_publication where task_id = :id")
                .param("id", taskId).query(String.class).single();
        assertThat(mapper.readTree(json).get("eventId").asText()).isEqualTo(first.eventId());
        assertThat(mapper.readTree(json).has("@class")).isFalse();
        assertThat(mapper.readTree(json).get("comments").get(0).get("body").asText()).isEqualTo("line comment 中文");
        assertThat(newRepository().findByTaskId(taskId)).contains(registered);
    }

    // Catches a digest that ignores any publication-affecting field, not only summaryBody.
    @Test
    void conflictingBusinessPayloadCannotOverwriteTheFirstRequest() {
        ReviewCompleted first = event("original summary");
        PublicationRecord saved = repository.register(first);
        List<ReviewCompleted> conflicts = List.of(
                new ReviewCompleted("e2", first.occurredAt(), taskId, "other/repo", 7, "head", first.summaryBody(), first.comments()),
                new ReviewCompleted("e2", first.occurredAt(), taskId, first.repo(), 8, "head", first.summaryBody(), first.comments()),
                new ReviewCompleted("e2", first.occurredAt(), taskId, first.repo(), 7, "other-head", first.summaryBody(), first.comments()),
                event("different summary"),
                new ReviewCompleted("e2", first.occurredAt(), taskId, first.repo(), 7, "head", first.summaryBody(), List.of()),
                new ReviewCompleted("e2", first.occurredAt(), taskId, first.repo(), 7, "head", first.summaryBody(),
                        List.of(new LineComment("Other.java", 3, "line comment 中文"))),
                new ReviewCompleted("e2", first.occurredAt(), taskId, first.repo(), 7, "head", first.summaryBody(),
                        List.of(new LineComment("src/A.java", 4, "line comment 中文"))),
                new ReviewCompleted("e2", first.occurredAt(), taskId, first.repo(), 7, "head", first.summaryBody(),
                        List.of(new LineComment("src/A.java", 3, "different comment"))));
        for (ReviewCompleted conflict : conflicts) {
            assertThatThrownBy(() -> repository.register(conflict)).isInstanceOf(IllegalStateException.class);
            assertThat(repository.findByTaskId(taskId)).contains(saved);
        }
        assertThat(jdbc.sql("select count(*) from integration.review_publication where task_id = :id")
                .param("id", taskId).query(Integer.class).single()).isEqualTo(1);
    }

    // Catches a read-then-update race; each contender owns a distinct live PG connection.
    @Test
    void onlyOneIndependentConnectionCanBeginTheReadyAttempt() throws Exception {
        repository.register(event("summary"));
        CyclicBarrier barrier = new CyclicBarrier(2);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var a = executor.submit(contender("attempt-a", barrier));
            var b = executor.submit(contender("attempt-b", barrier));
            AttemptOutcome first = a.get(15, TimeUnit.SECONDS);
            AttemptOutcome second = b.get(15, TimeUnit.SECONDS);
            assertThat(first.backendPid()).isNotEqualTo(second.backendPid());
            assertThat(List.of(first.started(), second.started())).containsExactlyInAnyOrder(true, false);
            PublicationRecord loaded = newRepository().findByTaskId(taskId).orElseThrow();
            assertThat(loaded.state()).isEqualTo(PublicationState.IN_FLIGHT);
            assertThat(loaded.attemptToken()).isEqualTo(first.started() ? "attempt-a" : "attempt-b");
            assertThat(newRepository().beginAttempt(taskId, "after-restart", PublicationMode.INLINE)).isFalse();
        }
    }

    // Catches a duplicate-key exception or overwriting the first event under concurrent delivery.
    @Test
    void concurrentRegistrationsKeepExactlyOneOriginalEvent() throws Exception {
        ReviewCompleted first = event("summary");
        ReviewCompleted second = new ReviewCompleted("delivery-2", first.occurredAt().plusSeconds(1),
                taskId, first.repo(), first.prNumber(), first.headSha(), first.summaryBody(), first.comments());
        CyclicBarrier barrier = new CyclicBarrier(2);
        PublicationRepository one = newRepository();
        PublicationRepository two = newRepository();
        try (var executor = Executors.newFixedThreadPool(2)) {
            var a = executor.submit(() -> {
                barrier.await(10, TimeUnit.SECONDS);
                return one.register(first);
            });
            var b = executor.submit(() -> {
                barrier.await(10, TimeUnit.SECONDS);
                return two.register(second);
            });
            PublicationRecord saved = a.get(15, TimeUnit.SECONDS);
            assertThat(b.get(15, TimeUnit.SECONDS)).isEqualTo(saved);
            assertThat(saved.event()).isIn(first, second);
            assertThat(repository.findByTaskId(taskId)).contains(saved);
        }
    }

    @Test
    void wrongExpectedModeCannotBeginAnAttempt() {
        repository.register(event("summary"));
        assertThat(repository.beginAttempt(taskId, "wrong-mode", PublicationMode.SUMMARY_ONLY)).isFalse();
        assertThat(repository.findByTaskId(taskId).orElseThrow().state()).isEqualTo(PublicationState.READY);
    }

    @Test
    void rejectionPersistsSummaryOnlyAcrossInstancesAndFencesTheOldToken() {
        repository.register(event("summary"));
        assertThat(repository.beginAttempt(taskId, "old", PublicationMode.INLINE)).isTrue();
        assertThat(repository.recordRejection(taskId, "wrong", PublicationMode.SUMMARY_ONLY, "422")).isFalse();
        assertThat(repository.recordRejection(taskId, "old", PublicationMode.SUMMARY_ONLY, "422 invalid position")).isTrue();
        PublicationRepository restarted = newRepository();
        PublicationRecord ready = restarted.findByTaskId(taskId).orElseThrow();
        assertThat(ready.state()).isEqualTo(PublicationState.READY);
        assertThat(ready.mode()).isEqualTo(PublicationMode.SUMMARY_ONLY);
        assertThat(ready.lastError()).isEqualTo("422 invalid position");
        assertThat(ready.attemptToken()).isNull();
        assertThat(restarted.beginAttempt(taskId, "stale-mode", PublicationMode.INLINE)).isFalse();
        assertThat(restarted.beginAttempt(taskId, "new", PublicationMode.SUMMARY_ONLY)).isTrue();
        assertThat(repository.markUncertain(taskId, "old", "late timeout")).isFalse();
        assertThat(repository.recordRejection(taskId, "old", PublicationMode.INLINE, "late rejection")).isFalse();
        assertThat(repository.recordSuccess(taskId, "old", new PublicationResult(1, 10, false))).isFalse();
        PublicationResult actual = new PublicationResult(9201L, 0, true);
        assertThat(restarted.recordSuccess(taskId, "new", actual)).isTrue();
        assertThat(repository.findByTaskId(taskId).orElseThrow().result()).isEqualTo(actual);
    }

    @Test
    void uncertainSurvivesRestartAndCannotAuthorizeRetryOrRejection() {
        repository.register(event("summary"));
        assertThat(repository.beginAttempt(taskId, "attempt", PublicationMode.INLINE)).isTrue();
        assertThat(repository.markUncertain(taskId, "wrong", "timeout")).isFalse();
        assertThat(repository.markUncertain(taskId, "attempt", "response lost")).isTrue();
        PublicationRepository restarted = newRepository();
        PublicationRecord uncertain = restarted.findByTaskId(taskId).orElseThrow();
        assertThat(uncertain.state()).isEqualTo(PublicationState.UNCERTAIN);
        assertThat(uncertain.lastError()).isEqualTo("response lost");
        assertThat(restarted.beginAttempt(taskId, "retry", PublicationMode.INLINE)).isFalse();
        assertThat(restarted.recordRejection(taskId, "attempt", PublicationMode.SUMMARY_ONLY, "late 422")).isFalse();
        assertThat(restarted.recordSuccess(taskId, "wrong", new PublicationResult(9, 1, false))).isFalse();
        assertThat(restarted.recordSuccess(taskId, "attempt", new PublicationResult(9202L, 1, false))).isTrue();
        assertThat(repository.findByTaskId(taskId).orElseThrow().lastError()).isNull();
    }

    @Test
    void successSavesActualCountsAndPublishedCannotBeOverwritten() {
        repository.register(event("summary"));
        assertThat(repository.recordSuccess(taskId, "attempt", new PublicationResult(9, 1, false))).isFalse();
        assertThat(repository.beginAttempt(taskId, "attempt", PublicationMode.INLINE)).isTrue();
        PublicationResult actual = new PublicationResult(9876543210L, 1, false);
        assertThat(repository.recordSuccess(taskId, "attempt", actual)).isTrue();
        PublicationRecord published = newRepository().findByTaskId(taskId).orElseThrow();
        assertThat(published.state()).isEqualTo(PublicationState.PUBLISHED);
        assertThat(published.result()).isEqualTo(actual);
        assertThat(published.updatedAt()).isAfterOrEqualTo(published.createdAt());
        assertThat(repository.beginAttempt(taskId, "later", PublicationMode.INLINE)).isFalse();
        assertThat(repository.recordRejection(taskId, "attempt", PublicationMode.SUMMARY_ONLY, "late 422")).isFalse();
        assertThat(repository.markUncertain(taskId, "attempt", "late timeout")).isFalse();
        assertThat(repository.recordSuccess(taskId, "attempt", new PublicationResult(99, 0, true))).isFalse();
        assertThat(repository.reconcileSuccess(taskId, new PublicationResult(100, 0, true))).isFalse();
        assertThat(repository.findByTaskId(taskId)).contains(published);
        assertThat(repository.register(event("summary"))).isEqualTo(published);
    }

    @Test
    void completeMarkerCanConfirmSuccessWithoutGrantingAnotherSend() {
        repository.register(event("summary"));
        repository.beginAttempt(taskId, "attempt", PublicationMode.INLINE);
        repository.markUncertain(taskId, "attempt", "lost response");
        PublicationResult foundOnScm = new PublicationResult(9203, 1, false);
        assertThat(repository.reconcileSuccess(taskId, foundOnScm)).isTrue();
        assertThat(newRepository().findByTaskId(taskId).orElseThrow().result()).isEqualTo(foundOnScm);
        assertThat(repository.beginAttempt(taskId, "retry", PublicationMode.INLINE)).isFalse();
    }

    @Test
    void completeMarkerCanConfirmAReadyRecord() {
        repository.register(event("summary"));
        PublicationResult foundOnScm = new PublicationResult(9204, 0, true);
        assertThat(repository.reconcileSuccess(taskId, foundOnScm)).isTrue();
        assertThat(repository.findByTaskId(taskId).orElseThrow().state()).isEqualTo(PublicationState.PUBLISHED);
    }

    @Test
    void completeMarkerCanConfirmAnInFlightRecord() {
        repository.register(event("summary"));
        repository.beginAttempt(taskId, "attempt", PublicationMode.INLINE);
        PublicationResult foundOnScm = new PublicationResult(9205, 1, false);
        assertThat(repository.reconcileSuccess(taskId, foundOnScm)).isTrue();
        assertThat(repository.findByTaskId(taskId).orElseThrow().result()).isEqualTo(foundOnScm);
        assertThat(repository.recordSuccess(taskId, "attempt", new PublicationResult(9206, 0, true))).isFalse();
    }

    @Test
    void negativeResultCannotPublishAnAttemptThroughEitherSuccessPath() {
        repository.register(event("summary"));
        repository.beginAttempt(taskId, "attempt", PublicationMode.INLINE);
        PublicationRecord before = repository.findByTaskId(taskId).orElseThrow();
        assertThatThrownBy(() -> repository.recordSuccess(taskId, "attempt", new PublicationResult(123, -1, false)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> repository.reconcileSuccess(taskId, new PublicationResult(123, -1, false)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(repository.findByTaskId(taskId)).contains(before);
    }

    @Test
    void databaseRejectsNegativeCountWithoutPublishingAnAttempt() {
        repository.register(event("summary"));
        repository.beginAttempt(taskId, "attempt", PublicationMode.INLINE);
        PublicationRecord before = repository.findByTaskId(taskId).orElseThrow();
        assertThatThrownBy(() -> jdbc.sql("""
                update integration.review_publication
                set state = 'PUBLISHED', review_id = 123, inline_count = -1, degraded = false
                where task_id = :id
                """).param("id", taskId).update())
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThat(repository.findByTaskId(taskId)).contains(before);
    }

    @Test
    void failedTransactionLeavesNoPartiallyInitializedRecord() {
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
            repository.register(event("summary"));
            throw new IllegalStateException("forced transaction rollback");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(newRepository().findByTaskId(taskId)).isEmpty();
    }

    @Test
    void missingTaskDoesNotCreateAnAttemptOrAResult() {
        assertThat(repository.findByTaskId(taskId)).isEmpty();
        assertThat(repository.beginAttempt(taskId, "attempt", PublicationMode.INLINE)).isFalse();
        assertThat(repository.markUncertain(taskId, "attempt", "timeout")).isFalse();
        assertThat(repository.recordRejection(taskId, "attempt", PublicationMode.SUMMARY_ONLY, "422")).isFalse();
        assertThat(repository.recordSuccess(taskId, "attempt", new PublicationResult(9, 0, true))).isFalse();
        assertThat(repository.reconcileSuccess(taskId, new PublicationResult(9, 0, true))).isFalse();
        assertThat(repository.findByTaskId(taskId)).isEmpty();
    }

    private Callable<AttemptOutcome> contender(String token, CyclicBarrier barrier) throws Exception {
        // Separate datasource objects ensure that Spring cannot reuse a transaction-bound connection.
        DriverManagerDataSource independent = independentDataSource();
        return () -> new TransactionTemplate(new DataSourceTransactionManager(independent)).execute(status -> {
            JdbcClient client = JdbcClient.create(independent);
            int pid = client.sql("select pg_backend_pid()").query(Integer.class).single();
            try {
                barrier.await(10, TimeUnit.SECONDS);
            } catch (Exception e) {
                throw new IllegalStateException("concurrency barrier failed", e);
            }
            PublicationRepository contender = new JdbcPublicationRepository(client, mapper);
            return new AttemptOutcome(pid, contender.beginAttempt(taskId, token, PublicationMode.INLINE));
        });
    }

    private PublicationRepository newRepository() throws RuntimeException {
        try {
            return new JdbcPublicationRepository(JdbcClient.create(independentDataSource()), mapper);
        } catch (Exception e) {
            throw new IllegalStateException("Cannot create independent PG repository", e);
        }
    }

    private DriverManagerDataSource independentDataSource() throws Exception {
        DriverManagerDataSource independent = new DriverManagerDataSource();
        try (var connection = dataSource.getConnection()) {
            independent.setUrl(connection.getMetaData().getURL());
            independent.setUsername(connection.getMetaData().getUserName());
        }
        independent.setPassword(System.getenv().getOrDefault("POSTGRES_PASSWORD", "codemind"));
        return independent;
    }

    private ReviewCompleted event(String summary) {
        return new ReviewCompleted("delivery-1", Instant.parse("2026-10-21T12:13:14.123456789Z"), taskId,
                "codemind/publication-it", 7, "head", summary,
                List.of(new LineComment("src/A.java", 3, "line comment 中文")));
    }

    private record AttemptOutcome(int backendPid, boolean started) {
    }
}
