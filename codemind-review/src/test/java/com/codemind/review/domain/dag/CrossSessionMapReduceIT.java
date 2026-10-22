package com.codemind.review.domain.dag;

import com.codemind.review.domain.agent.ChunkSummary;
import com.codemind.review.domain.agent.GlobalReduceAgent;
import com.codemind.review.domain.agent.ReviewAgent;
import com.codemind.review.domain.agent.ReviewDimension;
import com.codemind.review.domain.changemap.ChangeMap;
import com.codemind.review.domain.changemap.ChangeMapAgent;
import com.codemind.review.domain.chunk.ChunkBudget;
import com.codemind.review.domain.chunk.JtokkitTokenEstimator;
import com.codemind.review.domain.chunk.SemanticChunker;
import com.codemind.review.domain.chunk.ChunkRepository;
import com.codemind.review.domain.finding.Finding;
import com.codemind.review.domain.task.ReviewTask;
import com.codemind.review.domain.task.ReviewTaskRepository;
import com.codemind.review.domain.task.ReviewTaskStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * M6 集成测试：真实 Spring 容器 + 真实中间件（checkpoint 落 PG）+ 真实 LLM（需 key）。
 *
 * <p>验证跨会话 Map-Reduce 在真实装配下跑通，并按 M6 设计审查检查清单最后一项
 * <b>检查 ChunkSummary 质量</b>——真实模型产出的 {@code changedSymbols} 是否带参数类型。
 *
 * <p>确定性断言在 {@link CrossSessionMapReduceTest} 与 {@link GlobalReduceNodeTest}；
 * 本 IT 对真实模型只做宽松检查（模型措辞不稳定），无真实 key 时按既有惯例 {@code assumeTrue} 跳过。
 */
// 不覆盖 spring.ai.openai.api-key，原因同 MultiAgentIntegrationIT（覆盖会盖掉真 key → 401 → 断言失败）
@SpringBootTest
@TestPropertySource(properties = "spring.cloud.nacos.discovery.enabled=false")
class CrossSessionMapReduceIT {

    @Autowired
    private Map<ReviewDimension, ReviewAgent> reviewAgents;

    @Autowired
    private ChangeMapAgent changeMapAgent;

    @Autowired
    private GlobalReduceAgent globalReduceAgent;

    @Autowired
    private NodeRunRepository nodeRunRepository;

    @Autowired
    private ChunkRepository chunkRepository;

    @Autowired
    private ReviewTaskRepository taskRepository;

    @Test
    void crossSessionMapReduceWithRealLlm() throws Exception {
        assumeTrue(hasRealKey(), "未设置真实 CODEMIND_LLM_API_KEY，跳过真实 LLM 跨会话 IT");

        // sourceEventId 必须是合法 UUID：JdbcReviewTaskRepository 对它做 UUID.fromString
        ReviewTask task = ReviewTask.create(
                "codemind/m6-it-" + java.util.UUID.randomUUID(), 1, "head-m6", "base-m6",
                java.util.UUID.randomUUID().toString());
        assertTrue(taskRepository.insertIfAbsent(task), "任务应插入成功");

        // 中转端点延迟波动大，实测轻量的 build-change-map 也可能超过 90s；真实 LLM IT 给足余量
        DagScheduler scheduler = new DagScheduler(nodeRunRepository, Duration.ofSeconds(240), 1);
        DagOrchestrator orchestrator =
                new DagOrchestrator(scheduler, reviewAgents, globalReduceAgent, taskRepository);

        BuildChangeMapNode changeMapNode = new BuildChangeMapNode("build-change-map", changeMapAgent);
        // 小 diff 预算：确保真实切片器把 4 文件 diff 切成多片，真的走多会话
        PlanChunksNode planNode = new PlanChunksNode("plan-chunks",
                new SemanticChunker(new JtokkitTokenEstimator()),
                chunkRepository, taskRepository, ChunkBudget.ofDiffBudget(150));

        NodeContext context = NodeContext.initial(task.taskId())
                .withSharedState("diffText", resource("diff/cross-signature.diff"))
                .withSharedState("newSources", Map.of(
                        "PaymentGateway.java", resource("diff/src/PaymentGateway.java"),
                        "RefundClient.java", resource("diff/src/RefundClient.java"),
                        "AuditLogger.java", resource("diff/src/AuditLogger.java"),
                        "NotificationSender.java", resource("diff/src/NotificationSender.java")));

        CrossSessionReviewResult result =
                orchestrator.executeCrossSessionReview(changeMapNode, planNode, task, context);

        // 1. 真实切片 → 多个子会话，且调用方与定义方分落不同片
        assertTrue(result.chunkResults().size() > 1,
                "超预算 PR 应切成多片，实际 " + result.chunkResults().size());
        long chunksTouchingCallee = result.chunkResults().values().stream()
                .filter(r -> r.summary() != null)
                .filter(r -> r.summary().changedSymbols().stream()
                        .anyMatch(s -> s.contains("submitRefund")))
                .count();
        long chunksTouchingCaller = result.chunkResults().values().stream()
                .filter(r -> r.summary() != null)
                .filter(r -> r.summary().changedSymbols().stream()
                        .anyMatch(s -> s.contains("refundOrder")))
                .count();
        System.out.println("含定义方的片数=" + chunksTouchingCallee
                + "，含调用方的片数=" + chunksTouchingCaller);

        // 2. ChangeMap：确定性签名变更必须在（与 LLM 是否降级无关）
        ChangeMap changeMap = result.changeMap();
        assertNotNull(changeMap);
        assertEquals(4, changeMap.files().size());
        assertEquals(1, changeMap.signatureChangesOnly().size(), "应检出 1 处参数类型变更");
        assertEquals("RefundClient#submitRefund(long, int)",
                changeMap.signatureChangesOnly().get(0).signature());
        System.out.println("ChangeMap 意图: " + changeMap.intent());
        System.out.println("ChangeMap 高风险文件: " + changeMap.highRiskFiles());

        // 3. ChunkSummary 质量：真实模型是否按要求带了参数类型
        int summariesWithTypedSymbols = 0;
        for (ChunkReduceResult chunkResult : result.chunkResults().values()) {
            ChunkSummary summary = chunkResult.summary();
            assertNotNull(summary, "每片都应有摘要（降级摘要也算）");
            System.out.println(summary.renderForPrompt());
            boolean typed = summary.changedSymbols().stream().anyMatch(s -> s.contains("("));
            if (typed) {
                summariesWithTypedSymbols++;
            }
        }
        assertTrue(summariesWithTypedSymbols > 0,
                "至少一片摘要的 changedSymbols 应带参数签名（prompt 已明确要求）");

        // 4. GlobalReduce 只读摘要也能产出意见（宽松：不卡具体措辞）
        System.out.println("全局归约降级: " + result.globalReduce().degraded()
                + "，跨文件意见 " + result.globalReduce().findings().size() + " 条");
        for (Finding f : result.globalReduce().findings()) {
            System.out.println("  [跨文件] " + f.severity() + " " + f.category()
                    + " @ " + f.file() + " | " + f.message());
        }

        // 5. 状态流转到 AGGREGATING（PUBLISHED 属 M7）
        assertEquals(ReviewTaskStatus.AGGREGATING, task.status());
        assertNotNull(task.changeMap());
        assertEquals(result.chunkResults().size(), task.chunkSummaries().size());
        assertEquals(result.allFindings(), task.findings());
    }

    private static boolean hasRealKey() {
        String key = System.getenv("CODEMIND_LLM_API_KEY");
        return key != null && !key.isBlank() && !key.equals("placeholder-no-key");
    }

    private static String resource(String path) {
        try (InputStream in = CrossSessionMapReduceIT.class.getClassLoader().getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalArgumentException("找不到测试资源: " + path);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
