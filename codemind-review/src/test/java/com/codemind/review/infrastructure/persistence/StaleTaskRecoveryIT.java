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

    @Autowired
    private ReviewTaskRepository repository;

    @Autowired
    private JdbcClient jdbcClient;

    private final String repo = "codemind/stale-it-" + UUID.randomUUID();

    @AfterEach
    void cleanUp() {
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
}
