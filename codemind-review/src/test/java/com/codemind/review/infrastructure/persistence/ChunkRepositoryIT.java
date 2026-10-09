package com.codemind.review.infrastructure.persistence;

import com.codemind.review.domain.chunk.ChunkRepository;
import com.codemind.review.domain.chunk.ReviewChunk;
import com.codemind.review.domain.task.ReviewTask;
import com.codemind.review.domain.task.ReviewTaskRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ChunkRepository 集成测试（M2 建表，M3 补接线验证）。
 */
@SpringBootTest(properties = {
        "spring.cloud.nacos.discovery.enabled=false",
        "spring.cloud.discovery.enabled=false"
})
class ChunkRepositoryIT {

    @Autowired
    private ChunkRepository chunkRepository;

    @Autowired
    private ReviewTaskRepository taskRepository;

    @Autowired
    private JdbcClient jdbcClient;

    /** 每轮用独立 repo 名，避免与其它用例或上次运行的数据冲突。 */
    private final String repo = "codemind/chunk-it-" + UUID.randomUUID();

    @AfterEach
    void cleanUp() {
        jdbcClient.sql("delete from review_chunk where task_id in (select task_id from review_task where repo = :repo)")
                .param("repo", repo)
                .update();
        jdbcClient.sql("delete from review_task where repo = :repo")
                .param("repo", repo)
                .update();
    }

    @Test
    void saveAndFindByTask() {
        // 先创建任务（满足外键约束）
        ReviewTask task = ReviewTask.create(repo, 100, "head-sha", "base-sha",
                UUID.randomUUID().toString());
        taskRepository.insertIfAbsent(task);

        ReviewChunk chunk1 = new ReviewChunk(task.taskId() + "-chunk-0", List.of("OrderService.java"),
                List.of("OrderService#cancel"), "diff content 1", 100, false);
        ReviewChunk chunk2 = new ReviewChunk(task.taskId() + "-chunk-1", List.of("InventoryClient.java"),
                List.of("InventoryClient#rollback"), "diff content 2", 150, false);

        chunkRepository.saveAll(task.taskId(), List.of(chunk1, chunk2));

        List<ReviewChunk> found = chunkRepository.findByTask(task.taskId());
        assertEquals(2, found.size());
        assertEquals(task.taskId() + "-chunk-0", found.get(0).chunkId());
        assertEquals(task.taskId() + "-chunk-1", found.get(1).chunkId());
        assertEquals(List.of("OrderService.java"), found.get(0).files());
    }

    @Test
    void replacesOldChunksOnResave() {
        ReviewTask task = ReviewTask.create(repo, 101, "head-sha2", "base-sha2",
                UUID.randomUUID().toString());
        taskRepository.insertIfAbsent(task);

        String chunkId = task.taskId() + "-chunk-x";
        ReviewChunk old = new ReviewChunk(chunkId, List.of("A.java"), List.of(), "old", 50, false);
        chunkRepository.saveAll(task.taskId(), List.of(old));

        ReviewChunk newer = new ReviewChunk(chunkId, List.of("B.java"), List.of(), "new", 60, false);
        chunkRepository.saveAll(task.taskId(), List.of(newer));

        List<ReviewChunk> found = chunkRepository.findByTask(task.taskId());
        assertEquals(1, found.size());
        assertEquals(List.of("B.java"), found.get(0).files());
        assertEquals("new", found.get(0).diffContent());
    }
}
