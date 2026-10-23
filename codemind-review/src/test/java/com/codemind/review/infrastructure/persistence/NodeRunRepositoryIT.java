package com.codemind.review.infrastructure.persistence;

import com.codemind.review.domain.chunk.ReviewChunk;
import com.codemind.review.domain.dag.ChunkReduceResult;
import com.codemind.review.domain.dag.NodeRun;
import com.codemind.review.domain.dag.NodeRunRepository;
import com.codemind.review.domain.dag.NodeRunStatus;
import com.codemind.review.domain.finding.Category;
import com.codemind.review.domain.finding.Finding;
import com.codemind.review.domain.finding.Severity;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * NodeRunRepository 集成测试（M3 checkpoint 持久化验证）。
 */
@SpringBootTest
class NodeRunRepositoryIT {

    @org.springframework.test.context.bean.override.convention.TestBean(name = "rocketMqDomainEventPublisher", methodName = "publicationTestPublisher")
    private com.codemind.review.domain.event.DomainEventPublisher publicationTestPublisher;

    static com.codemind.review.domain.event.DomainEventPublisher publicationTestPublisher() {
        return new com.codemind.review.support.RecordingDomainEventPublisher();
    }

    @Autowired
    private NodeRunRepository repository;

    @Test
    void saveAndFindByTaskAndNode() {
        NodeRun run = NodeRun.started("task-it-1", "node-A");
        repository.save(run);

        Optional<NodeRun> found = repository.findByTaskAndNode("task-it-1", "node-A");
        assertTrue(found.isPresent());
        assertEquals("node-A", found.get().nodeId());
        assertEquals(NodeRunStatus.RUNNING, found.get().status());
    }

    @Test
    void updateStatusOnConflict() {
        NodeRun running = NodeRun.started("task-it-2", "node-B");
        repository.save(running);

        NodeRun completed = running.complete("result-B");
        repository.save(completed);

        Optional<NodeRun> found = repository.findByTaskAndNode("task-it-2", "node-B");
        assertTrue(found.isPresent());
        assertEquals(NodeRunStatus.COMPLETED, found.get().status());
        assertNotNull(found.get().completedAt());
    }

    @Test
    void findCompletedByTask() {
        NodeRun run1 = NodeRun.started("task-it-3", "node-C");
        NodeRun completed1 = run1.complete("result-C");
        repository.save(completed1);

        NodeRun run2 = NodeRun.started("task-it-3", "node-D");
        NodeRun completed2 = run2.complete("result-D");
        repository.save(completed2);

        NodeRun run3 = NodeRun.started("task-it-3", "node-E");
        repository.save(run3); // RUNNING

        List<NodeRun> completed = repository.findCompletedByTask("task-it-3");
        assertEquals(2, completed.size());
        assertTrue(completed.stream().allMatch(NodeRun::isCompleted));
    }

    /**
     * M7a：复杂结果必须按原类型还原。
     *
     * <p>原先反序列化用 {@code readValue(json, Object.class)}，拿回来是 {@code LinkedHashMap}，
     * 跨阶段取上游结果一转型就炸——{@code DagOrchestrator} 三次调 {@code scheduler.execute}，
     * 第三次 loadCheckpoint 读回第二次写的 ChunkReduceResult，{@code GlobalReduceNode} 直接失败。
     * 纯单测用内存桩绕过了序列化，所以一直没暴露（M3 技术债，M6 验证点实际被它堵死）。
     */
    @Test
    void 复杂结果按原类型还原而非LinkedHashMap() {
        Finding finding = Finding.of("A.java", 12, Severity.MAJOR, Category.CORRECTNESS,
                "空指针风险", "加判空", "token.trim()");
        ChunkReduceResult original =
                new ChunkReduceResult("chunk-0", List.of(finding), List.of("某维度降级"));
        String taskId = "task-it-roundtrip-" + UUID.randomUUID();

        repository.save(NodeRun.started(taskId, "reduce-chunk-0").complete(original));

        Object restored = repository.findByTaskAndNode(taskId, "reduce-chunk-0")
                .orElseThrow().result();

        assertInstanceOf(ChunkReduceResult.class, restored,
                "必须还原成 ChunkReduceResult，实际: " + restored.getClass());
        ChunkReduceResult typed = (ChunkReduceResult) restored;
        assertEquals("chunk-0", typed.chunkId());
        assertEquals(1, typed.findings().size());
        assertEquals("A.java", typed.findings().get(0).file());
        assertEquals(Severity.MAJOR, typed.findings().get(0).severity());
        assertEquals(List.of("某维度降级"), typed.degradations());
    }

    /** 泛型集合也要还原（PlanChunks 产出 {@code List<ReviewChunk>}）。 */
    @Test
    void 泛型集合结果还原后元素类型正确() {
        ReviewChunk chunk = new ReviewChunk("chunk-9", List.of("A.java"),
                List.of("A#go()"), "diff --git a/A.java b/A.java\n", 42, false);
        String taskId = "task-it-generic-" + UUID.randomUUID();

        repository.save(NodeRun.started(taskId, "plan-chunks").complete(List.of(chunk)));

        Object restored = repository.findByTaskAndNode(taskId, "plan-chunks")
                .orElseThrow().result();

        assertInstanceOf(List.class, restored);
        List<?> list = (List<?>) restored;
        assertEquals(1, list.size());
        assertInstanceOf(ReviewChunk.class, list.get(0),
                "元素必须还原成 ReviewChunk，实际: " + list.get(0).getClass());
        assertEquals("chunk-9", ((ReviewChunk) list.get(0)).chunkId());
    }
}
