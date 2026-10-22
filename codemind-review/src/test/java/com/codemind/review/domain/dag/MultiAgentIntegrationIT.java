package com.codemind.review.domain.dag;

import com.codemind.review.domain.agent.ReviewAgent;
import com.codemind.review.domain.agent.ReviewDimension;
import com.codemind.review.domain.chunk.ReviewChunk;
import com.codemind.review.domain.finding.Finding;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * M5 集成测试：真实 Spring 容器 + 真实 LLM（可选），验证多 Agent 并行 + 去重完整流程。
 */
// 不要在这里覆盖 spring.ai.openai.api-key：application.yml 已是
// ${CODEMIND_LLM_API_KEY:placeholder-no-key}，没 key 时自动落占位值、上下文照样能起。
// 硬写占位值会把环境变量里的真 key 盖掉，而 hasRealKey() 读的是 System.getenv——
// 它看得见真 key 所以不跳过，于是每次调用 401、降级成空结果、断言失败（M5/M6 的既有缺陷）。
@SpringBootTest
@TestPropertySource(properties = "spring.cloud.nacos.discovery.enabled=false")
class MultiAgentIntegrationIT {

    @Autowired
    private Map<ReviewDimension, ReviewAgent> reviewAgents;

    @Autowired
    private NodeRunRepository nodeRunRepository;

    @Test
    void multiAgentParallelAndDedupWithRealLlm() throws DagExecutionException {
        // 只在有真实 key 时跑，否则跳过（与 ReviewAgentIT 一致）
        assumeTrue(hasRealKey(), "未设置真实 CODEMIND_LLM_API_KEY，跳过真实 LLM 多 Agent IT");

        DagScheduler scheduler = new DagScheduler(nodeRunRepository, Duration.ofSeconds(30), 2);
        DagOrchestrator orchestrator = new DagOrchestrator(scheduler, reviewAgents);

        // 构造一个含明显问题的 chunk（空指针风险 + 异常吞没 + 命名问题）
        String diff = """
                diff --git a/OrderService.java b/OrderService.java
                index aaa..bbb 100644
                --- a/OrderService.java
                +++ b/OrderService.java
                @@ -5,3 +5,7 @@ public class OrderService {
                     public void cancel(Order order) {
                -        validate(order);
                +        try {
                +            String token = order.getToken();
                +            gateway.cancel(token.trim());
                +        } catch (Exception e) {
                +        }
                     }
                """;

        ReviewChunk chunk = new ReviewChunk(
                "chunk-order", List.of("OrderService.java"), List.of("OrderService#cancel"),
                diff, 150, false);

        // 模拟 PlanChunks 产出
        Node planNode = new StubPlanChunksNode("plan", List.of(chunk));
        NodeContext context = NodeContext.initial("task-it-" + System.currentTimeMillis());

        Map<String, ChunkReduceResult> results = orchestrator.executeReview(planNode, context);

        // 验证：多维度 Agent 并行评审 + 去重
        assertEquals(1, results.size(), "单个 chunk 产出一个归约结果");
        ChunkReduceResult result = results.get("chunk-order");
        assertNotNull(result);

        List<Finding> findings = result.findings();
        System.out.println("4 个维度 Agent 并行评审后，去重得到 " + findings.size() + " 条 Finding");

        // 至少应发现一些问题（真实模型会找到空指针、异常吞没等）
        assertFalse(findings.isEmpty(), "真实 LLM 应至少发现 1 个问题");

        // 验证 fingerprint 去重生效：所有 Finding 的 fingerprint 互不相同
        long distinctFingerprints = findings.stream()
                .map(Finding::fingerprint)
                .distinct()
                .count();
        assertEquals(findings.size(), distinctFingerprints,
                "去重后的 Finding 指纹应全部唯一（无重复）");

        for (Finding f : findings) {
            System.out.println("  - [" + f.severity() + "] " + f.category() + " @ "
                    + f.file() + ":" + f.line() + " | " + f.message());
        }
    }

    private static boolean hasRealKey() {
        String key = System.getenv("CODEMIND_LLM_API_KEY");
        return key != null && !key.isBlank() && !key.equals("placeholder-no-key");
    }

    /** Stub PlanChunksNode */
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
}
