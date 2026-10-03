package com.codemind.review.infrastructure.persistence;

import com.codemind.review.domain.task.ReviewTask;
import com.codemind.review.domain.task.ReviewTaskRepository;
import com.codemind.review.domain.task.ReviewTaskStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;
import java.util.UUID;

/**
 * 基于 JdbcClient 的仓储实现。domain 层保持无框架依赖，映射集中在这里。
 */
@Repository
public class JdbcReviewTaskRepository implements ReviewTaskRepository {

    private static final String INSERT_SQL = """
            insert into review_task
                (task_id, repo, pr_number, head_sha, base_sha, source_event_id, status, created_at, updated_at)
            values (:taskId, :repo, :prNumber, :headSha, :baseSha, :sourceEventId, :status, :createdAt, :createdAt)
            on conflict (repo, pr_number, head_sha) do nothing
            """;

    private static final String SELECT_SQL = """
            select task_id, repo, pr_number, head_sha, base_sha, source_event_id, status, created_at
            from review_task
            where repo = :repo and pr_number = :prNumber and head_sha = :headSha
            """;

    private final JdbcClient jdbcClient;

    public JdbcReviewTaskRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    @Override
    public boolean insertIfAbsent(ReviewTask task) {
        // on conflict do nothing：并发或重放时影响行数为 0，不抛异常，调用方据此判重。
        int affected = jdbcClient.sql(INSERT_SQL)
                .param("taskId", UUID.fromString(task.taskId()))
                .param("repo", task.repo())
                .param("prNumber", task.prNumber())
                .param("headSha", task.headSha())
                .param("baseSha", task.baseSha())
                .param("sourceEventId", UUID.fromString(task.sourceEventId()))
                .param("status", task.status().name())
                .param("createdAt", java.sql.Timestamp.from(task.createdAt()))
                .update();
        return affected > 0;
    }

    @Override
    public Optional<ReviewTask> findByPrHead(String repo, int prNumber, String headSha) {
        return jdbcClient.sql(SELECT_SQL)
                .param("repo", repo)
                .param("prNumber", prNumber)
                .param("headSha", headSha)
                .query(this::mapRow)
                .optional();
    }

    private ReviewTask mapRow(ResultSet rs, int rowNum) throws SQLException {
        return ReviewTask.rehydrate(
                rs.getString("task_id"),
                rs.getString("repo"),
                rs.getInt("pr_number"),
                rs.getString("head_sha"),
                rs.getString("base_sha"),
                rs.getString("source_event_id"),
                ReviewTaskStatus.valueOf(rs.getString("status")),
                rs.getTimestamp("created_at").toInstant());
    }
}
