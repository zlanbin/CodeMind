package com.codemind.memory.infrastructure.persistence;

import com.codemind.memory.domain.fingerprint.TaskFingerprintRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class JdbcTaskFingerprintRepositoryIT {

    @Autowired
    TaskFingerprintRepository repository;
    @Autowired
    JdbcClient jdbc;

    private final String repo = "codemind/fp-it-" + UUID.randomUUID();

    @AfterEach
    void cleanUp() {
        jdbc.sql("delete from memory.task_fingerprint where repo = :r").param("r", repo).update();
    }

    @Test
    void addsOnlyNewFingerprintsScopedByPullRequest() {
        assertThat(repository.addAll(repo, 1, List.of("a", "b", "a"))).isEqualTo(2);
        assertThat(repository.addAll(repo, 1, List.of("b", "c"))).isEqualTo(1);
        assertThat(repository.addAll(repo, 2, List.of("a"))).isEqualTo(1);

        assertThat(repository.findAll(repo, 1)).containsExactlyInAnyOrder("a", "b", "c");
        assertThat(repository.findAll(repo, 2)).containsExactly("a");
        assertThat(repository.findAll(repo, 3)).isEmpty();
    }
}
