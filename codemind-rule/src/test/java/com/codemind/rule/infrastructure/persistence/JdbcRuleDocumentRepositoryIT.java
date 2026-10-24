package com.codemind.rule.infrastructure.persistence;

import com.codemind.rule.domain.rule.RuleDocument;
import com.codemind.rule.domain.rule.RuleDocumentRepository;
import com.codemind.rule.domain.rule.RuleDocumentStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class JdbcRuleDocumentRepositoryIT {

    private static final Instant T0 = Instant.parse("2026-10-24T00:00:00Z");

    @Autowired
    RuleDocumentRepository repository;
    @Autowired
    JdbcClient jdbc;

    private final String project = "codemind/rule-it-" + UUID.randomUUID();

    @AfterEach
    void cleanUp() {
        jdbc.sql("delete from rule.chunk where project = :p").param("p", project).update();
        jdbc.sql("delete from rule.document where project = :p").param("p", project).update();
    }

    @Test
    void insertedDocumentCanBeFoundWithCorrectFields() {
        RuleDocument doc = repository.insert(project, "接口设计规范", 1, T0);

        assertThat(doc.id()).isPositive();
        assertThat(doc.project()).isEqualTo(project);
        assertThat(doc.title()).isEqualTo("接口设计规范");
        assertThat(doc.status()).isEqualTo(RuleDocumentStatus.ACTIVE);
        assertThat(doc.version()).isEqualTo(1);
    }

    @Test
    void archiveActiveByProjectAndTitleArchivesExistingActiveVersion() {
        repository.insert(project, "接口设计规范", 1, T0);

        var archivedVersion = repository.archiveActiveByProjectAndTitle(project, "接口设计规范");

        assertThat(archivedVersion).contains(1);
        Integer activeCount = jdbc.sql(
                "select count(*) from rule.document where project = :p and title = :t and status = 'ACTIVE'")
                .param("p", project).param("t", "接口设计规范").query(Integer.class).single();
        assertThat(activeCount).isZero();
    }

    @Test
    void archiveActiveByProjectAndTitleReturnsEmptyWhenNoActiveVersionExists() {
        var result = repository.archiveActiveByProjectAndTitle(project, "不存在的文档");

        assertThat(result).isEmpty();
    }
}
