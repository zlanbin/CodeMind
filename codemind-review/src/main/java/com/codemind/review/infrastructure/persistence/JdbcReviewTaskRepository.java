package com.codemind.review.infrastructure.persistence;

import com.codemind.review.domain.task.ReviewTask;
import com.codemind.review.domain.task.ReviewTaskRepository;
import com.codemind.review.domain.task.ReviewTaskStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

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

    /** 已有发布请求的任务不算卡死：评审已完成，交给 outbox 补发，恢复不该把它判成卡死重跑评审。 */
    private static final String NO_OUTBOX = """
            not exists (select 1 from review_publication_outbox o where o.task_id = t.task_id)
            """;

    private static final String NO_OUTBOX_BY_PARAM = """
            not exists (select 1 from review_publication_outbox o where o.task_id = :taskId)
            """;

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
                  and\s""" + NO_OUTBOX;

    /**
     * 原子抢占：状态仍在进行中、且无发布请求才改得动。
     *
     * <p>单条 UPDATE 做不到这件事：把 {@code NOT EXISTS} 并进同一个 {@code FOR UPDATE} 子查询
     * 看起来该在锁等待后被重新判定，但实测（手工两会话 SQL 复现，见 PG IT「完成交接与恢复抢占
     * 竞争」）并非如此——PostgreSQL 的 {@code FOR UPDATE} 只保证被锁行本身的最新版本可见，
     * 单条语句开始时对 {@code review_publication_outbox} 这张无关表取的快照不会因为等锁而刷新，
     * 等拿到锁时，哪怕 register 刚提交了 outbox 行，这条 UPDATE 依然看不到，照样把已经交接完成
     * 的任务错误地抢成 FAILED。
     *
     * <p>必须拆成两条语句、放进同一个显式事务：先 {@code SELECT ... FOR UPDATE} 拿到行锁
     * （这一步会真的等 register 的事务提交释放锁），锁拿到之后 PostgreSQL 在 READ COMMITTED
     * 下为下一条语句开一个新快照，这时再查 {@code NOT EXISTS} 才能看见刚提交的 outbox 行。
     */
    private static final String CLAIM_LOCK_SQL = """
            select task_id from review_task
            where task_id = :taskId and status in """ + IN_PROGRESS_STATUSES + " for update";

    private static final String CLAIM_CHECK_OUTBOX_SQL = "select " + NO_OUTBOX_BY_PARAM;

    private static final String CLAIM_UPDATE_SQL = """
            update review_task set status = 'FAILED', updated_at = now()
            where task_id = :taskId and status in """ + IN_PROGRESS_STATUSES;

    /**
     * {@code markFailedIfUnpublished} 的行锁语句：结构与 {@code CLAIM_LOCK_SQL} 相同，
     * 理由同上方注释——单条 {@code UPDATE ... AND NOT EXISTS(outbox)} 挡不住
     * {@link com.codemind.review.application.ReviewCompletionService#register} 的并发交接
     * （S6 评审发现：原来只改了 {@code claimForRecovery}，这个结构相同的方法漏改了）。
     * 排除 {@code PUBLISHED} 而不是枚举进行中状态：评审执行失败时任务可能处在任何非终态。
     */
    private static final String MARK_FAILED_LOCK_SQL = """
            select task_id from review_task where task_id = :taskId and status <> 'PUBLISHED' for update
            """;

    private static final String MARK_FAILED_UPDATE_SQL = """
            update review_task set status = 'FAILED', updated_at = now()
            where task_id = :taskId and status <> 'PUBLISHED'
            """;

    private static final String SELECT_BY_ID_SQL = """
            select task_id, repo, pr_number, head_sha, base_sha, source_event_id, status, created_at
            from review_task
            where task_id = :taskId
            """;

    private final JdbcClient jdbcClient;

    /**
     * claimForRecovery / markFailedIfUnpublished 的正确性依赖「同事务内第二条语句能看见
     * 第一条语句等锁期间刚提交的数据」——这是 READ COMMITTED 的特性（S6 评审 I-3）。
     * 不用容器默认隔离级别（可能被别处改掉而默默失效），显式锁定隔离级别。
     */
    private final TransactionTemplate readCommittedTransactions;

    public JdbcReviewTaskRepository(JdbcClient jdbcClient,
                                    PlatformTransactionManager transactionManager) {
        this.jdbcClient = jdbcClient;
        this.readCommittedTransactions = new TransactionTemplate(transactionManager);
        this.readCommittedTransactions.setIsolationLevel(
                TransactionDefinition.ISOLATION_READ_COMMITTED);
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
    public Optional<ReviewTask> findByIdForUpdate(String taskId) {
        return jdbcClient.sql(SELECT_BY_ID_SQL + " for update")
                .param("taskId", UUID.fromString(taskId)).query(this::mapRow).optional();
    }

    @Override
    public boolean markFailedIfUnpublished(String taskId) {
        return Boolean.TRUE.equals(readCommittedTransactions.execute(status -> {
            // 第一条语句拿行锁：会等 register 事务提交释放锁，拿到后这一行的 status 一定是最新的。
            boolean notPublished = jdbcClient.sql(MARK_FAILED_LOCK_SQL)
                    .param("taskId", UUID.fromString(taskId)).query(String.class).optional().isPresent();
            if (!notPublished) {
                return false;
            }
            // 第二条语句另开快照：锁等待期间 register 提交的 outbox 行，在这里才能看见。
            boolean noOutbox = jdbcClient.sql(CLAIM_CHECK_OUTBOX_SQL)
                    .param("taskId", UUID.fromString(taskId)).query(Boolean.class).single();
            if (!noOutbox) {
                return false;
            }
            return jdbcClient.sql(MARK_FAILED_UPDATE_SQL).param("taskId", UUID.fromString(taskId)).update() > 0;
        }));
    }

    @Override
    public boolean markPublishedIfAggregating(String taskId) {
        return jdbcClient.sql("""
                update review_task set status='PUBLISHED',updated_at=now()
                where task_id=:taskId and status='AGGREGATING'
                """).param("taskId", UUID.fromString(taskId)).update() > 0;
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
        return Boolean.TRUE.equals(readCommittedTransactions.execute(status -> {
            // 第一条语句拿行锁：会等 register 事务提交释放锁，拿到后这一行的 status 一定是最新的。
            boolean stillInProgress = jdbcClient.sql(CLAIM_LOCK_SQL)
                    .param("taskId", UUID.fromString(taskId)).query(String.class).optional().isPresent();
            if (!stillInProgress) {
                return false;
            }
            // 第二条语句另开快照：锁等待期间 register 提交的 outbox 行，在这里才能看见。
            boolean noOutbox = jdbcClient.sql(CLAIM_CHECK_OUTBOX_SQL)
                    .param("taskId", UUID.fromString(taskId)).query(Boolean.class).single();
            if (!noOutbox) {
                return false;
            }
            return jdbcClient.sql(CLAIM_UPDATE_SQL).param("taskId", UUID.fromString(taskId)).update() > 0;
        }));
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
