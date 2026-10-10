package com.codemind.review.infrastructure.persistence;

import com.codemind.review.domain.task.ReviewTask;
import com.codemind.review.domain.task.ReviewTaskRepository;
import com.codemind.review.domain.task.ReviewTaskStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 仓储与唯一索引幂等，跑在真实 PostgreSQL 上（Flyway 已建表）。
 *
 * <p>前置条件：{@code cd deploy && docker compose up -d}。
 */
@SpringBootTest(properties = {
        "spring.cloud.nacos.discovery.enabled=false",
        "spring.cloud.discovery.enabled=false"
})
class ReviewTaskRepositoryIT {

    @Autowired
    private ReviewTaskRepository repository;

    @Autowired
    private JdbcClient jdbcClient;

    /** 每轮用独立 repo 名，避免与其它用例或上次运行的数据冲突。 */
    private final String repo = "codemind/it-" + UUID.randomUUID();

    @AfterEach
    void cleanUp() {
        jdbcClient.sql("delete from review_task where repo = :repo")
                .param("repo", repo)
                .update();
    }

    @Test
    void 插入后可按PR与headSha查出() {
        ReviewTask task = ReviewTask.create(repo, 7, "headsha", "basesha", UUID.randomUUID().toString());

        assertThat(repository.insertIfAbsent(task)).isTrue();

        Optional<ReviewTask> found = repository.findByPrHead(repo, 7, "headsha");
        assertThat(found).isPresent();
        ReviewTask loaded = found.orElseThrow();
        assertThat(loaded.taskId()).isEqualTo(task.taskId());
        assertThat(loaded.status()).isEqualTo(ReviewTaskStatus.CREATED);
        assertThat(loaded.baseSha()).isEqualTo("basesha");
        assertThat(loaded.sourceEventId()).isEqualTo(task.sourceEventId());
    }

    @Test
    void 同repo同PR同headSha第二次插入被唯一索引拦住() {
        ReviewTask first = ReviewTask.create(repo, 7, "headsha", "basesha", UUID.randomUUID().toString());
        ReviewTask second = ReviewTask.create(repo, 7, "headsha", "basesha", UUID.randomUUID().toString());

        assertThat(repository.insertIfAbsent(first)).isTrue();
        assertThat(repository.insertIfAbsent(second)).isFalse();

        assertThat(countRows()).isEqualTo(1);
        // 留下的是第一条
        assertThat(repository.findByPrHead(repo, 7, "headsha").orElseThrow().taskId())
                .isEqualTo(first.taskId());
    }

    @Test
    void 同PR不同headSha视为两个任务() {
        assertThat(repository.insertIfAbsent(
                ReviewTask.create(repo, 7, "sha-1", "basesha", UUID.randomUUID().toString()))).isTrue();
        assertThat(repository.insertIfAbsent(
                ReviewTask.create(repo, 7, "sha-2", "basesha", UUID.randomUUID().toString()))).isTrue();

        assertThat(countRows()).isEqualTo(2);
    }

    @Test
    void 查不到时返回空() {
        assertThat(repository.findByPrHead(repo, 999, "nope")).isEmpty();
    }

    @Test
    void 按taskId查回重建的聚合() {
        ReviewTask task = ReviewTask.create(repo, 7, "headsha", "basesha", UUID.randomUUID().toString());
        assertThat(repository.insertIfAbsent(task)).isTrue();

        ReviewTask found = repository.findById(task.taskId()).orElseThrow();

        assertThat(found.taskId()).isEqualTo(task.taskId());
        assertThat(found.repo()).isEqualTo(repo);
        assertThat(found.prNumber()).isEqualTo(7);
        assertThat(found.headSha()).isEqualTo("headsha");
        assertThat(found.baseSha()).isEqualTo("basesha");
        assertThat(found.status()).isEqualTo(ReviewTaskStatus.CREATED);
    }

    @Test
    void 按未知taskId查返回空() {
        assertThat(repository.findById(UUID.randomUUID().toString())).isEmpty();
    }

    private int countRows() {
        return jdbcClient.sql("select count(*) from review_task where repo = :repo")
                .param("repo", repo)
                .query(Integer.class)
                .single();
    }
}
