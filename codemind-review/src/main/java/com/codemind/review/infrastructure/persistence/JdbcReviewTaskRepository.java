package com.codemind.review.infrastructure.persistence;

import com.codemind.review.domain.task.ReviewTask;
import com.codemind.review.domain.task.ReviewTaskRepository;
import com.codemind.review.domain.task.ReviewTaskStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
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

    private static final String IN_PROGRESS_STATUSES = "('PLANNING', 'REVIEWING', 'AGGREGATING')";

    /**
     * 卡死判定：任务自身 updated_at 早于截止时间，<b>且</b>它名下节点的最后活动时间也早于截止
     * （没有节点记录的只看 updated_at）。两个条件都要，因为 updated_at 只在阶段切换时刷新，
     * 而 REVIEWING 阶段几十个 LLM 调用期间它一直不动——只看它会把正在跑的任务误判成卡死。
     */
    private static final String SELECT_STALE_SQL = """
            select t.task_id
            from review_task t
            where t.status in """ + IN_PROGRESS_STATUSES + """
                  and t.updated_at < :cutoff
                  and coalesce(
                        (select max(coalesce(n.completed_at, n.started_at))
                         from node_run n where n.task_id = cast(t.task_id as varchar)),
                        t.updated_at) < :cutoff
            """;

    /** 原子抢占：状态仍在进行中才改得动，多实例同时扫只有一个拿到受影响行。 */
    private static final String CLAIM_SQL = """
            update review_task
            set status = 'FAILED', updated_at = now()
            where task_id = :taskId and status in """ + IN_PROGRESS_STATUSES;

    private static final String SELECT_BY_ID_SQL = """
            select task_id, repo, pr_number, head_sha, base_sha, source_event_id, status, created_at
            from review_task
            where task_id = :taskId
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

    @Override
    public Optional<ReviewTask> findById(String taskId) {
        return jdbcClient.sql(SELECT_BY_ID_SQL)
                .param("taskId", UUID.fromString(taskId))
                .query(this::mapRow)
                .optional();
    }

    @Override
    public List<String> findStaleInProgress(Duration idleFor) {
        Timestamp cutoff = Timestamp.from(Instant.now().minus(idleFor));
        return jdbcClient.sql(SELECT_STALE_SQL)
                .param("cutoff", cutoff)
                .query(String.class)
                .list();
    }

    @Override
    public boolean claimForRecovery(String taskId) {
        return jdbcClient.sql(CLAIM_SQL)
                .param("taskId", UUID.fromString(taskId))
                .update() > 0;
    }

    @Override
    public void updateStatus(String taskId, ReviewTaskStatus status) {
        jdbcClient.sql("""
                update review_task
                set status = :status, updated_at = now()
                where task_id = :taskId
                """)
                .param("taskId", UUID.fromString(taskId))
                .param("status", status.name())
                .update();
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
