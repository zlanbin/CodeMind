package com.codemind.review.domain.dag;

import com.codemind.review.domain.agent.*;
import com.codemind.review.domain.chunk.ReviewChunk;
import com.codemind.review.domain.finding.Category;
import com.codemind.review.domain.finding.Finding;
import com.codemind.review.domain.finding.Severity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link DagOrchestrator} 纯单测：验证动态扇出 + 多 Agent 并行 + 去重（M5 核心验证点）。
 *
 * <p>用 stub LLM 返回预设 Finding，验证：
 * <ul>
 *   <li>每个 chunk × 4 维度 = 4 个 ReviewChunkNode 并行执行；</li>
 *   <li>相同 fingerprint 的 Finding 只保留一条；</li>
 *   <li>多个 chunk 各自独立归约。</li>
 * </ul>
 */
class DagOrchestratorTest {

    private InMemoryNodeRunRepository repo;
    private DagScheduler scheduler;
    private Map<ReviewDimension, ReviewAgent> agents;

    @BeforeEach
    void setUp() {
        repo = new InMemoryNodeRunRepository();
        scheduler = new DagScheduler(repo, Duration.ofSeconds(10), 1);

        // 4 个 stub Agent：每个返回固定 Finding
        FindingJsonParser parser = new FindingJsonParser();
        StubLlmClient styleLlm = new StubLlmClient("""
                {"findings":[{"file":"A.java","line":1,"severity":"MINOR","category":"STYLE",
                "message":"命名问题","suggestion":"改名","codeSnippet":"int x"}]}
                """);
        StubLlmClient securityLlm = new StubLlmClient("""
                {"findings":[{"file":"A.java","line":1,"severity":"MAJOR","category":"STYLE",
                "message":"严重命名问题","suggestion":"必须改名","codeSnippet":"int x"}]}
                """);  // 与 style 同 fingerprint，severity 更高
        StubLlmClient perfLlm = new StubLlmClient("""
                {"findings":[{"file":"A.java","line":10,"severity":"CRITICAL","category":"PERFORMANCE",
                "message":"性能问题","suggestion":"优化","codeSnippet":"loop"}]}
                """);
        StubLlmClient testLlm = new StubLlmClient("""
                {"findings":[]}
                """);

        agents = Map.of(
                ReviewDimension.STYLE, new ReviewAgent(ReviewDimension.STYLE, styleLlm, parser, 0),
                ReviewDimension.SECURITY, new ReviewAgent(ReviewDimension.SECURITY, securityLlm, parser, 0),
                ReviewDimension.PERFORMANCE, new ReviewAgent(ReviewDimension.PERFORMANCE, perfLlm, parser, 0),
                ReviewDimension.TEST, new ReviewAgent(ReviewDimension.TEST, testLlm, parser, 0)
        );
    }

    @Test
    void dynamicFanOutAndDedup() throws DagExecutionException {
        DagOrchestrator orchestrator = new DagOrchestrator(scheduler, agents);

        // 模拟 PlanChunksNode 产出 2 个 chunk
        List<ReviewChunk> chunks = List.of(
                new ReviewChunk("chunk-1", List.of("A.java"), List.of("A#foo"),
                        "@@ -1,2 +1,3 @@\n+int x = 1;", 10, false),
                new ReviewChunk("chunk-2", List.of("B.java"), List.of("B#bar"),
                        "@@ -1,2 +1,3 @@\n+int y = 2;", 10, false)
        );
        Node planNode = new StubPlanChunksNode("plan", chunks);

        NodeContext context = NodeContext.initial("task-1");
        Map<String, ChunkReduceResult> results = orchestrator.executeReview(planNode, context);

        assertEquals(2, results.size(), "2 个 chunk 各产出一个归约结果");

        ChunkReduceResult r1 = results.get("chunk-1");
        assertNotNull(r1);
        assertEquals(2, r1.findings().size(), "style + security 同 fingerprint → 去重后保留 1 条，加 perf 共 2 条");

        // 验证保留了 severity 更高的 security 意见
        List<String> messages = r1.findings().stream().map(Finding::message).toList();
        assertTrue(messages.contains("严重命名问题"), "应保留 MAJOR 的 security 意见");
        assertFalse(messages.contains("命名问题"), "MINOR 的 style 意见应被覆盖");
        assertTrue(messages.contains("性能问题"));

        ChunkReduceResult r2 = results.get("chunk-2");
        assertNotNull(r2);
        assertEquals(2, r2.findings().size(), "chunk-2 同样产出 2 条去重后意见");
    }

    @Test
    void emptyChunksReturnsEmptyResult() throws DagExecutionException {
        DagOrchestrator orchestrator = new DagOrchestrator(scheduler, agents);
        Node planNode = new StubPlanChunksNode("plan", List.of());

        Map<String, ChunkReduceResult> results = orchestrator.executeReview(planNode, NodeContext.initial("task-1"));

        assertTrue(results.isEmpty(), "无 chunk 时应返回空 map");
    }

    /** Stub PlanChunksNode：直接返回预设的 chunk 列表，不实际切片。 */
    private static class StubPlanChunksNode implements Node {
        private final String nodeId;
        private final List<ReviewChunk> chunks;

        StubPlanChunksNode(String nodeId, List<ReviewChunk> chunks) {
            this.nodeId = nodeId;
            this.chunks = chunks;
        }

        @Override
        public String id() {
            return nodeId;
        }

        @Override
        public List<ReviewChunk> execute(NodeContext context) {
            return chunks;
        }
    }

    /** Stub LLM：返回固定 JSON 字符串。 */
    private static class StubLlmClient implements LlmClient {
        private final String response;

        StubLlmClient(String response) {
            this.response = response;
        }

        @Override
        public String complete(String systemPrompt, String userPrompt) {
            return response;
        }
    }

    /** 内存版 NodeRunRepository（测试用）。 */
    private static class InMemoryNodeRunRepository implements NodeRunRepository {
        private final Map<String, NodeRun> store = new ConcurrentHashMap<>();

        @Override
        public void save(NodeRun nodeRun) {
            String key = nodeRun.taskId() + ":" + nodeRun.nodeId();
            store.put(key, nodeRun);
        }

        @Override
        public java.util.Optional<NodeRun> findByTaskAndNode(String taskId, String nodeId) {
            return java.util.Optional.ofNullable(store.get(taskId + ":" + nodeId));
        }

        @Override
        public List<NodeRun> findCompletedByTask(String taskId) {
            return store.values().stream()
                    .filter(r -> r.taskId().equals(taskId) && r.isCompleted())
                    .toList();
        }
    }
}
