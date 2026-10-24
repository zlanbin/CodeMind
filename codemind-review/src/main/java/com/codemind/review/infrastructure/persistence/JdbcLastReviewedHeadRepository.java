package com.codemind.review.infrastructure.persistence;

import com.codemind.review.domain.task.LastReviewedHead;
import com.codemind.review.domain.task.LastReviewedHeadRepository;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;

@Repository
public class JdbcLastReviewedHeadRepository implements LastReviewedHeadRepository {

    private static final String UPSERT_SQL = """
            insert into last_reviewed_head (repo, pr_number, base_sha, head_sha, updated_at)
            values (:repo, :prNumber, :baseSha, :headSha, now())
            on conflict (repo, pr_number) do update set
                base_sha = excluded.base_sha,
                head_sha = excluded.head_sha,
                updated_at = excluded.updated_at
            """;

    private static final String SELECT_SQL = """
            select repo, pr_number, base_sha, head_sha, updated_at
            from last_reviewed_head
            where repo = :repo and pr_number = :prNumber
            """;

    private final JdbcClient jdbc;

    public JdbcLastReviewedHeadRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<LastReviewedHead> findFor(String repo, int prNumber) {
        return jdbc.sql(SELECT_SQL)
                .param("repo", repo)
                .param("prNumber", prNumber)
                .query(this::mapRow)
                .optional();
    }

    @Override
    public void upsert(String repo, int prNumber, String baseSha, String headSha) {
        jdbc.sql(UPSERT_SQL)
                .param("repo", repo)
                .param("prNumber", prNumber)
                .param("baseSha", baseSha)
                .param("headSha", headSha)
                .update();
    }

    private LastReviewedHead mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new LastReviewedHead(
                rs.getString("repo"),
                rs.getInt("pr_number"),
                rs.getString("base_sha"),
                rs.getString("head_sha"),
                rs.getTimestamp("updated_at").toInstant());
    }
}
