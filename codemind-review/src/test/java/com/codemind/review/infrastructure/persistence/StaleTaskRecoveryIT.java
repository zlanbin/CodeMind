package com.codemind.review.infrastructure.persistence;

import com.codemind.review.domain.task.ReviewTask;
import com.codemind.review.domain.task.ReviewTaskRepository;
import com.codemind.review.domain.task.ReviewTaskStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 卡死判定 SQL 跑在真实 PostgreSQL 上（M7a 任务 8）。
 *
 * <p>核心风险是<b>把正在跑的任务误判成卡死</b>——那会重复调 LLM。
 * {@code 有新鲜节点活动的任务不算卡死} 专盯这条。
 *
 * <p>前置条件：{@code cd deploy && docker compose up -d}。
 */
@SpringBootTest(properties = {
        "spring.cloud.nacos.discovery.enabled=false",
        "spring.cloud.discovery.enabled=false"
})
class StaleTaskRecoveryIT {

    @org.springframework.test.context.bean.override.convention.TestBean(name = "rocketMqDomainEventPublisher", methodName = "publicationTestPublisher")
    private com.codemind.review.domain.event.DomainEventPublisher publicationTestPublisher;

    static com.codemind.review.domain.event.DomainEventPublisher publicationTestPublisher() {
        return new com.codemind.review.support.RecordingDomainEventPublisher();
    }

    @Autowired
    private ReviewTaskRepository repository;

    @Autowired
    private JdbcClient jdbcClient;

    @Autowired
    private com.codemind.review.application.ReviewCompletionService completionService;

    @Autowired
    private org.springframework.transaction.support.TransactionTemplate transactionTemplate;

    @Autowired
    private com.fasterxml.jackson.databind.ObjectMapper objectMapper;

    private final String repo = "codemind/stale-it-" + UUID.randomUUID();

    @AfterEach
    void cleanUp() {
        jdbcClient.sql("""
                delete from review_publication_outbox where task_id in
                    (select task_id from review_task where repo = :repo)
                """).param("repo", repo).update();
        jdbcClient.sql("""
                delete from node_run where task_id in
                    (select cast(task_id as varchar) from review_task where repo = :repo)
                """).param("repo", repo).update();
        jdbcClient.sql("delete from review_task where repo = :repo").param("repo", repo).update();
    }

    @Test
    void 空闲超阈值的进行中任务被捞到() {
        String taskId = insertTask(ReviewTaskStatus.REVIEWING);
        backdateTask(taskId, 30);

        List<String> stale = repository.findStaleInProgress(Duration.ofMinutes(15));

        assertThat(stale).contains(taskId);
    }

    @Test
    void 有新鲜节点活动的任务不算卡死() {
        String taskId = insertTask(ReviewTaskStatus.REVIEWING);
        backdateTask(taskId, 30);
        // 任务状态 30 分钟没动，但有节点正在跑——这是正常的长任务，绝不能重启
        insertRunningNode(taskId);

        List<String> stale = repository.findStaleInProgress(Duration.ofMinutes(15));

        assertThat(stale).doesNotContain(taskId);
    }

    /**
     * 「两者都早于阈值」的另一半：节点活动很旧，但任务状态刚推进过（比如刚从 PLANNING 进 REVIEWING，
     * 下一个节点还没落 started_at）。只看 node_run 就会误判（S6 评审补）。
     */
    @Test
    void 状态刚推进过的任务即使节点活动很旧也不算卡死() {
        String taskId = insertTask(ReviewTaskStatus.REVIEWING);
        insertCompletedNode(taskId, 30);

        List<String> stale = repository.findStaleInProgress(Duration.ofMinutes(15));

        assertThat(stale).doesNotContain(taskId);
    }

    /** 两侧都旧才判卡死：防止 node_run 的 join 写错后把有节点记录的任务一律排除。 */
    @Test
    void 状态与节点活动都旧的任务被捞到() {
        String taskId = insertTask(ReviewTaskStatus.REVIEWING);
        backdateTask(taskId, 30);
        insertCompletedNode(taskId, 30);

        List<String> stale = repository.findStaleInProgress(Duration.ofMinutes(15));

        assertThat(stale).contains(taskId);
    }

    @Test
    void 终态与CREATED任务不被捞到() {
        String published = insertTask(ReviewTaskStatus.PUBLISHED);
        String failed = insertTask(ReviewTaskStatus.FAILED);
        String created = insertTask(ReviewTaskStatus.CREATED);
        backdateTask(published, 60);
        backdateTask(failed, 60);
        backdateTask(created, 60);

        List<String> stale = repository.findStaleInProgress(Duration.ofMinutes(15));

        assertThat(stale).doesNotContain(published, failed, created);
    }

    @Test
    void 有发布请求的卡死任务不被扫到也抢不到() {
        String taskId = insertTask(ReviewTaskStatus.AGGREGATING);
        backdateTask(taskId, 30);
        insertOutboxRequest(taskId);

        List<String> stale = repository.findStaleInProgress(Duration.ofMinutes(15));

        assertThat(stale).doesNotContain(taskId);
        assertThat(repository.claimForRecovery(taskId)).isFalse();
        assertThat(repository.findById(taskId).orElseThrow().status())
                .isEqualTo(ReviewTaskStatus.AGGREGATING);
    }

    /**
     * 完成交接（register）先赢行锁：claimForRecovery 必须等它提交、重新判定 outbox 后拒绝抢占。
     * 用真实屏障而非概率竞争——register 事务内先拿到行锁后用 {@code pg_sleep} 卡住自己，
     * 确认它已经持锁（{@code pg_locks} 能查到）才放行 claim 线程，不靠谁先被调度。
     */
    @Test
    void 完成交接先拿到行锁时恢复抢占必须等待并最终拒绝() throws Exception {
        String taskId = insertTask(ReviewTaskStatus.AGGREGATING);
        backdateTask(taskId, 30);
        var registerHoldsLock = new java.util.concurrent.CountDownLatch(1);

        try (var pool = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var registerResult = pool.submit(() ->
                    registerWithLockHoldSignal(taskId, registerHoldsLock, Duration.ofSeconds(2)));

            assertThat(registerHoldsLock.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            assertThat(waitUntilAnyOtherTransactionHoldsReviewTaskLock(Duration.ofSeconds(3))).isTrue();

            long start = System.nanoTime();
            boolean claimed = repository.claimForRecovery(taskId);
            long waitedMs = (System.nanoTime() - start) / 1_000_000;

            registerResult.get(5, java.util.concurrent.TimeUnit.SECONDS);

            // claimForRecovery 的调用本身必须被 register 的行锁卡住等待，不是侥幸先跑完
            assertThat(waitedMs).isGreaterThanOrEqualTo(1500);
            assertThat(claimed).isFalse();
            assertThat(repository.findById(taskId).orElseThrow().status())
                    .isEqualTo(ReviewTaskStatus.AGGREGATING);
            assertThat(outboxExists(taskId)).isTrue();
        }
    }

    /**
     * 恢复抢占先赢行锁：register 必须等它提交、重新判定状态后拒绝交接
     * （状态已被改成 FAILED，不允许再插入 outbox 把它伪装成已交接）。
     */
    @Test
    void 恢复抢占先拿到行锁时完成交接必须等待并最终拒绝() throws Exception {
        String taskId = insertTask(ReviewTaskStatus.AGGREGATING);
        backdateTask(taskId, 30);
        var claimHoldsLock = new java.util.concurrent.CountDownLatch(1);

        try (var pool = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var claimResult = pool.submit(() ->
                    claimWithLockHoldSignal(taskId, claimHoldsLock, Duration.ofSeconds(2)));

            assertThat(claimHoldsLock.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            assertThat(waitUntilAnyOtherTransactionHoldsReviewTaskLock(Duration.ofSeconds(3))).isTrue();

            long start = System.nanoTime();
            boolean registered;
            try {
                insertOutboxRequest(taskId);
                registered = true;
            } catch (IllegalStateException e) {
                // S6 评审 N-4：只吞 register 任务状态不对时抛的这一种异常，
                // 其它失败（NPE、连接耗尽、SQL 错误）必须冒泡，不能被误判成「被正确拒绝」。
                registered = false;
            }
            long waitedMs = (System.nanoTime() - start) / 1_000_000;

            assertThat(claimResult.get(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            // register 的调用本身必须被 claim 的行锁卡住等待，不是侥幸先跑完
            assertThat(waitedMs).isGreaterThanOrEqualTo(1500);
            assertThat(registered).isFalse();
            assertThat(repository.findById(taskId).orElseThrow().status())
                    .isEqualTo(ReviewTaskStatus.FAILED);
            assertThat(outboxExists(taskId)).isFalse();
        }
    }

    /**
     * S6 评审发现（C-1）：{@code markFailedIfUnpublished}（{@code ReviewExecutionService.markFailed}
     * 调用的失败路径）跟 {@code claimForRecovery} 结构相同，但当时没有同步修复，
     * 对 register 的并发交接同样会裸读旧快照。本用例直接验证该方法，不是 claimForRecovery。
     * 场景：register 先拿到行锁并卡住——markFailedIfUnpublished 必须等它提交、
     * 重新判定 outbox 后拒绝把任务改成 FAILED。
     */
    @Test
    void 完成交接先拿到行锁时markFailedIfUnpublished必须等待并最终拒绝() throws Exception {
        String taskId = insertTask(ReviewTaskStatus.AGGREGATING);
        var registerHoldsLock = new java.util.concurrent.CountDownLatch(1);

        try (var pool = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var registerResult = pool.submit(() ->
                    registerWithLockHoldSignal(taskId, registerHoldsLock, Duration.ofSeconds(2)));

            assertThat(registerHoldsLock.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            assertThat(waitUntilAnyOtherTransactionHoldsReviewTaskLock(Duration.ofSeconds(3))).isTrue();

            long start = System.nanoTime();
            boolean markedFailed = repository.markFailedIfUnpublished(taskId);
            long waitedMs = (System.nanoTime() - start) / 1_000_000;

            registerResult.get(5, java.util.concurrent.TimeUnit.SECONDS);

            // markFailedIfUnpublished 的调用本身必须被 register 的行锁卡住等待，不是侥幸先跑完
            assertThat(waitedMs).isGreaterThanOrEqualTo(1500);
            assertThat(markedFailed).isFalse();
            assertThat(repository.findById(taskId).orElseThrow().status())
                    .isEqualTo(ReviewTaskStatus.AGGREGATING);
            assertThat(outboxExists(taskId)).isTrue();
        }
    }

    /** 反向：markFailedIfUnpublished 先赢行锁时，register 必须等它提交、看到 FAILED 后拒绝交接。 */
    @Test
    void markFailedIfUnpublished先拿到行锁时完成交接必须等待并最终拒绝() throws Exception {
        String taskId = insertTask(ReviewTaskStatus.AGGREGATING);
        var markFailedHoldsLock = new java.util.concurrent.CountDownLatch(1);

        try (var pool = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var markFailedResult = pool.submit(() ->
                    markFailedWithLockHoldSignal(taskId, markFailedHoldsLock, Duration.ofSeconds(2)));

            assertThat(markFailedHoldsLock.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            assertThat(waitUntilAnyOtherTransactionHoldsReviewTaskLock(Duration.ofSeconds(3))).isTrue();

            long start = System.nanoTime();
            boolean registered;
            try {
                insertOutboxRequest(taskId);
                registered = true;
            } catch (IllegalStateException e) {
                // S6 评审 N-4：同上，只吞 register 因状态不对而拒绝的这一种异常。
                registered = false;
            }
            long waitedMs = (System.nanoTime() - start) / 1_000_000;

            assertThat(markFailedResult.get(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            assertThat(waitedMs).isGreaterThanOrEqualTo(1500);
            assertThat(registered).isFalse();
            assertThat(repository.findById(taskId).orElseThrow().status())
                    .isEqualTo(ReviewTaskStatus.FAILED);
            assertThat(outboxExists(taskId)).isFalse();
        }
    }

    /** 复刻 markFailedIfUnpublished 的锁序，同样先发信号再 pg_sleep 卡住，不提交 UPDATE。 */
    private boolean markFailedWithLockHoldSignal(String taskId, java.util.concurrent.CountDownLatch holdsLock,
                                                  Duration holdFor) {
        return Boolean.TRUE.equals(transactionTemplate.execute(status -> {
            boolean notPublished = jdbcClient.sql(
                    "select task_id from review_task where task_id = :taskId and status <> 'PUBLISHED' for update")
                    .param("taskId", UUID.fromString(taskId)).query(String.class).optional().isPresent();
            holdsLock.countDown();
            if (!notPublished) {
                return false;
            }
            jdbcClient.sql("select pg_sleep(:seconds)")
                    .param("seconds", (double) holdFor.toSeconds()).query((rs, n) -> null).list();
            if (outboxExists(taskId)) {
                return false;
            }
            return jdbcClient.sql("update review_task set status = 'FAILED', updated_at = now() "
                            + "where task_id = :taskId and status <> 'PUBLISHED'")
                    .param("taskId", UUID.fromString(taskId)).update() > 0;
        }));
    }

    /**
     * 复刻 {@code ReviewCompletionService.register} 的锁序（先 {@code findByIdForUpdate} 拿行锁），
     * 但在拿到锁之后先发信号、再用 {@code pg_sleep} 卡住事务不提交——这样调用方能确认锁已真的持有，
     * 而不是靠线程调度顺序赌谁先跑到临界区。卡住结束后才真正插入 outbox 并提交。
     */
    private boolean registerWithLockHoldSignal(String taskId, java.util.concurrent.CountDownLatch holdsLock,
                                                Duration holdFor) {
        try {
            return Boolean.TRUE.equals(transactionTemplate.execute(status -> {
                var locked = repository.findByIdForUpdate(taskId);
                if (locked.isEmpty() || locked.get().status() != ReviewTaskStatus.AGGREGATING) {
                    holdsLock.countDown();
                    return false;
                }
                holdsLock.countDown();
                jdbcClient.sql("select pg_sleep(:seconds)")
                        .param("seconds", (double) holdFor.toSeconds()).query((rs, n) -> null).list();
                insertOutboxRowWithinCurrentTransaction(taskId);
                return true;
            }));
        } catch (RuntimeException e) {
            holdsLock.countDown();
            return false;
        }
    }

    /** 复刻 {@code claimForRecovery} 的锁序，同样先发信号再 {@code pg_sleep} 卡住，不提交 UPDATE。 */
    private boolean claimWithLockHoldSignal(String taskId, java.util.concurrent.CountDownLatch holdsLock,
                                            Duration holdFor) {
        return Boolean.TRUE.equals(transactionTemplate.execute(status -> {
            boolean stillInProgress = jdbcClient.sql(
                    "select task_id from review_task where task_id = :taskId and status in "
                            + "('PLANNING', 'REVIEWING', 'AGGREGATING') for update")
                    .param("taskId", UUID.fromString(taskId)).query(String.class).optional().isPresent();
            holdsLock.countDown();
            if (!stillInProgress) {
                return false;
            }
            jdbcClient.sql("select pg_sleep(:seconds)")
                    .param("seconds", (double) holdFor.toSeconds()).query((rs, n) -> null).list();
            boolean noOutbox = !outboxExists(taskId);
            if (!noOutbox) {
                return false;
            }
            return jdbcClient.sql("update review_task set status = 'FAILED', updated_at = now() "
                            + "where task_id = :taskId and status in ('PLANNING', 'REVIEWING', 'AGGREGATING')")
                    .param("taskId", UUID.fromString(taskId)).update() > 0;
        }));
    }

    private void insertOutboxRowWithinCurrentTransaction(String taskId) {
        var task = repository.findById(taskId).orElseThrow();
        var event = com.codemind.common.event.ReviewCompleted.of(taskId, task.repo(), task.prNumber(),
                task.headSha(), "summary", java.util.List.of());
        try {
            jdbcClient.sql("""
                    insert into review_publication_outbox(task_id,event_id,payload)
                    values (:taskId,:eventId,cast(:payload as jsonb))
                    """).param("taskId", UUID.fromString(taskId))
                    .param("eventId", UUID.fromString(event.eventId()))
                    .param("payload", objectMapper.writeValueAsString(event)).update();
            jdbcClient.sql("update review_task set status = 'AGGREGATING', updated_at = now() where task_id = :taskId")
                    .param("taskId", UUID.fromString(taskId)).update();
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private boolean outboxExists(String taskId) {
        return jdbcClient.sql("select count(*) from review_publication_outbox where task_id=:id")
                .param("id", UUID.fromString(taskId)).query(Integer.class).single() > 0;
    }

    /**
     * S6 评审 N-3 更正：这不是按 taskId 精确定位的行级锁检查——{@code pg_locks} 的
     * relation 级记录只到表级（{@code SELECT ... FOR UPDATE} 取的表级锁是
     * {@code RowShareLock}），任何并发的普通 SELECT 都会让它返回 true，判据偏宽。
     * 真正的确定性屏障是持锁线程「先拿到行锁、再 countDown、再 pg_sleep 卡住不提交」
     * 那段逻辑本身（见各用例的 {@code waitedMs >= 1500} 断言）；这里只是在那之前
     * 做一次宽松的二次确认——等到「有别的事务在 review_task 上持有任何锁」，
     * 降低信号发得过早时轮询到错误状态的概率，不提供按 taskId 的精确性。
     */
    private boolean waitUntilAnyOtherTransactionHoldsReviewTaskLock(Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            Integer count = jdbcClient.sql("""
                    select count(*) from pg_locks l
                    join pg_class c on l.relation = c.oid
                    where c.relname = 'review_task' and l.granted and l.pid <> pg_backend_pid()
                    """).query(Integer.class).single();
            if (count != null && count > 0) {
                return true;
            }
            Thread.sleep(50);
        }
        return false;
    }

    @Test
    void 抢占只成功一次() {
        String taskId = insertTask(ReviewTaskStatus.AGGREGATING);
        backdateTask(taskId, 30);

        assertThat(repository.claimForRecovery(taskId)).isTrue();
        assertThat(repository.claimForRecovery(taskId)).isFalse();
        assertThat(repository.findById(taskId).orElseThrow().status())
                .isEqualTo(ReviewTaskStatus.FAILED);
    }

    /** 直接按目标状态插入，绕开状态机（模拟进程在该阶段死掉的落库现状）。 */
    private String insertTask(ReviewTaskStatus status) {
        ReviewTask task = ReviewTask.create(repo, 7, "head-" + UUID.randomUUID(),
                "base", UUID.randomUUID().toString());
        repository.insertIfAbsent(task);
        if (status != ReviewTaskStatus.CREATED) {
            jdbcClient.sql("update review_task set status = :status where task_id = :taskId")
                    .param("status", status.name())
                    .param("taskId", UUID.fromString(task.taskId()))
                    .update();
        }
        return task.taskId();
    }

    private void backdateTask(String taskId, int minutesAgo) {
        jdbcClient.sql("""
                update review_task
                set updated_at = now() - make_interval(mins => :minutes)
                where task_id = :taskId
                """)
                .param("minutes", minutesAgo)
                .param("taskId", UUID.fromString(taskId))
                .update();
    }

    private void insertRunningNode(String taskId) {
        jdbcClient.sql("""
                insert into node_run (run_id, task_id, node_id, status, started_at)
                values (:runId, :taskId, 'review-chunk-1', 'RUNNING', now())
                """)
                .param("runId", UUID.randomUUID())
                .param("taskId", taskId)
                .update();
    }

    /** 走真实完成交接服务：它在事务内先 {@code findByIdForUpdate} 锁住 task 行，
     * 这正是它与 {@code claimForRecovery} 必须互斥的那把锁——绕开它插库测不出真实竞争。 */
    private void insertOutboxRequest(String taskId) {
        var task = repository.findById(taskId).orElseThrow();
        var event = com.codemind.common.event.ReviewCompleted.of(taskId, task.repo(), task.prNumber(),
                task.headSha(), "summary", java.util.List.of());
        completionService.register(event);
    }

    private void insertCompletedNode(String taskId, int minutesAgo) {
        jdbcClient.sql("""
                insert into node_run (run_id, task_id, node_id, status, started_at, completed_at)
                values (:runId, :taskId, 'plan-chunks', 'COMPLETED',
                        now() - make_interval(mins => :minutes + 1), now() - make_interval(mins => :minutes))
                """)
                .param("runId", UUID.randomUUID())
                .param("taskId", taskId)
                .param("minutes", minutesAgo)
                .update();
    }
}
