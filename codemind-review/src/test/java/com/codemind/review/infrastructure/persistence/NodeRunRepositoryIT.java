package com.codemind.review.infrastructure.persistence;

import com.codemind.review.domain.dag.NodeRun;
import com.codemind.review.domain.dag.NodeRunRepository;
import com.codemind.review.domain.dag.NodeRunStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * NodeRunRepository 集成测试（M3 checkpoint 持久化验证）。
 */
@SpringBootTest
class NodeRunRepositoryIT {

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
}
