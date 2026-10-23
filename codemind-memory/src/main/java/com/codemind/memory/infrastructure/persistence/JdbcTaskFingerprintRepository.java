package com.codemind.memory.infrastructure.persistence;

import com.codemind.memory.domain.fingerprint.TaskFingerprintRepository;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;

@Repository
public class JdbcTaskFingerprintRepository implements TaskFingerprintRepository {

    private final JdbcClient jdbc;

    public JdbcTaskFingerprintRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional
    public int addAll(String repo, int prNumber, Collection<String> fingerprints) {
        int added = 0;
        for (String fingerprint : new LinkedHashSet<>(fingerprints)) {
            added += jdbc.sql("""
                    insert into memory.task_fingerprint (repo, pr_number, fingerprint)
                    values (:repo, :pr, :fp)
                    on conflict do nothing
                    """)
                    .param("repo", repo).param("pr", prNumber).param("fp", fingerprint)
                    .update();
        }
        return added;
    }

    @Override
    public Set<String> findAll(String repo, int prNumber) {
        return new LinkedHashSet<>(jdbc.sql("""
                        select fingerprint from memory.task_fingerprint
                        where repo = :repo and pr_number = :pr order by fingerprint
                        """)
                .param("repo", repo).param("pr", prNumber)
                .query(String.class).list());
    }
}
