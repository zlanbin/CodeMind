package com.codemind.review.infrastructure.persistence;

import com.codemind.review.domain.task.LastReviewedHeadRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 仓储覆盖写语义，跑在真实 PostgreSQL 上（Flyway 已建表，M10 V6）。
 *
 * <p>前置条件：{@code cd deploy && docker compose up -d}。
 */
@SpringBootTest(properties = {
        "spring.cloud.nacos.discovery.enabled=false",
        "spring.cloud.discovery.enabled=false"
})
class LastReviewedHeadRepositoryIT {

    @org.springframework.test.context.bean.override.convention.TestBean(name = "rocketMqDomainEventPublisher", methodName = "publicationTestPublisher")
    private com.codemind.review.domain.event.DomainEventPublisher publicationTestPublisher;

    static com.codemind.review.domain.event.DomainEventPublisher publicationTestPublisher() {
        return new com.codemind.review.support.RecordingDomainEventPublisher();
    }

    @Autowired
    private LastReviewedHeadRepository repository;

    @Autowired
    private JdbcClient jdbcClient;

    private final String repo = "codemind/it-" + UUID.randomUUID();

    @AfterEach
    void cleanUp() {
        jdbcClient.sql("delete from last_reviewed_head where repo = :repo")
                .param("repo", repo)
                .update();
    }

    @Test
    void upsertOverwritesExistingRowForSamePrNumber() {
        repository.upsert(repo, 1, "base1", "head1");
        repository.upsert(repo, 1, "base1", "head2");

        var found = repository.findFor(repo, 1);
        assertTrue(found.isPresent());
        assertEquals("head2", found.get().headSha(), "第二次 upsert 应覆盖 head_sha，不应插出第二行");
    }

    @Test
    void findForReturnsEmptyWhenNoRecord() {
        assertTrue(repository.findFor(repo, 999).isEmpty());
    }
}
