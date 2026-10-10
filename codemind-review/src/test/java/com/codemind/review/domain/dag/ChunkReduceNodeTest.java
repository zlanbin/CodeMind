package com.codemind.review.domain.dag;

import com.codemind.review.domain.agent.*;
import com.codemind.review.domain.chunk.ReviewChunk;
import com.codemind.review.domain.finding.Category;
import com.codemind.review.domain.finding.Finding;
import com.codemind.review.domain.finding.Severity;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link ChunkReduceNode} 纯单测：验证 M5 的 fingerprint 去重（验证点：同一问题被多个 Agent 命中只保留 1 条）。
 */
class ChunkReduceNodeTest {

    /** 模拟 4 个维度 Agent 评审同一 chunk，部分问题有相同 fingerprint（同一处同类问题）。 */
    @Test
    void deduplicatesSameFingerprintAcrossAgents() throws DagExecutionException {
        // 4 个 Agent 各自返回的结果（style + security 都发现了同一个问题 "变量命名不规范"）
        Finding f1 = Finding.of("OrderService.java", 10, Severity.MINOR, Category.STYLE,
                "变量名应用驼峰", "改为 orderCount", "int x");
        Finding f2 = Finding.of("OrderService.java", 10, Severity.MAJOR, Category.STYLE,
                "命名不符合规范", "改名", "int x");  // 与 f1 fingerprint 相同，severity 更高
        Finding f3 = Finding.of("OrderService.java", 15, Severity.CRITICAL, Category.SECURITY,
                "SQL 注入风险", "用参数化查询", "query + x");
        Finding f4 = Finding.of("OrderService.java", 20, Severity.INFO, Category.PERFORMANCE,
                "可以用 StringBuilder", "优化字符串拼接", "s + x");

        AgentReview styleReview = AgentReview.success(List.of(f1, f2), 1);
        AgentReview securityReview = AgentReview.success(List.of(f3), 1);
        AgentReview perfReview = AgentReview.success(List.of(f4), 1);
        AgentReview testReview = AgentReview.success(List.of(), 1);  // 无问题

        // 构造上下文：4 个 ReviewChunkNode 的结果
        NodeContext context = NodeContext.initial("task-1")
                .withCompletedResult("review-chunk-1-style", styleReview)
                .withCompletedResult("review-chunk-1-security", securityReview)
                .withCompletedResult("review-chunk-1-performance", perfReview)
                .withCompletedResult("review-chunk-1-test", testReview);

        ChunkReduceNode node = new ChunkReduceNode(
                "reduce-chunk-1",
                "chunk-1",
                List.of("review-chunk-1-style", "review-chunk-1-security",
                        "review-chunk-1-performance", "review-chunk-1-test"));

        ChunkReduceResult result = node.execute(context);

        assertFalse(result.hasDegradation());
        assertEquals(3, result.findings().size(), "4 条原始 Finding（f1+f2 同指纹）→ 去重后 3 条");

        // 验证 f1/f2（相同 fingerprint）只保留 severity 更高的 f2（MAJOR > MINOR）
        List<String> messages = result.findings().stream().map(Finding::message).toList();
        assertTrue(messages.contains("命名不符合规范"), "应保留 severity 更高的 f2");
        assertFalse(messages.contains("变量名应用驼峰"), "f1 应被 f2 覆盖");
    }

    @Test
    void collectsDegradationInfo() throws DagExecutionException {
        AgentReview styleSuccess = AgentReview.success(List.of(), 1);
        AgentReview securityDegraded = AgentReview.degraded("超时", 3);
        AgentReview perfDegraded = AgentReview.degraded("解析失败", 3);
        AgentReview testSuccess = AgentReview.success(List.of(), 1);

        NodeContext context = NodeContext.initial("task-1")
                .withCompletedResult("n1", styleSuccess)
                .withCompletedResult("n2", securityDegraded)
                .withCompletedResult("n3", perfDegraded)
                .withCompletedResult("n4", testSuccess);

        ChunkReduceNode node = new ChunkReduceNode("reduce-1", "chunk-1", List.of("n1", "n2", "n3", "n4"));
        ChunkReduceResult result = node.execute(context);

        assertTrue(result.hasDegradation());
        assertEquals(2, result.degradations().size());
        assertTrue(result.degradations().contains("超时"));
        assertTrue(result.degradations().contains("解析失败"));
    }

    @Test
    void throwsIfSourceNodeNotCompleted() {
        NodeContext context = NodeContext.initial("task-1");
        ChunkReduceNode node = new ChunkReduceNode("reduce-1", "chunk-1", List.of("missing"));

        assertThrows(IllegalStateException.class, () -> node.execute(context),
                "上游节点未完成应抛异常");
    }

    // ---------- M6：多维度摘要合并与完整性校验 ----------

    @Test
    void mergesSummariesFromAllDimensions() throws DagExecutionException {
        // 各维度看的是同一份 diff，符号本应一致；取并集以容忍某个维度漏写
        ChunkSummary styleSummary = ChunkSummary.of("chunk-1",
                List.of("RefundClient#submitRefund(long, int)"), "票据号改成 long",
                List.of("命名含义变了"), List.of());
        ChunkSummary securitySummary = ChunkSummary.of("chunk-1",
                List.of("RefundClient#submitRefund(long, int)", "RefundClient#send(long, int)"),
                "票据号参数类型变更", List.of("无输入校验"),
                List.of("调用方是否已同步改为 long？"));

        NodeContext context = NodeContext.initial("task-1")
                .withCompletedResult("n1", AgentReview.success(List.of(), styleSummary, 1))
                .withCompletedResult("n2", AgentReview.success(List.of(), securitySummary, 1));

        ChunkReduceResult result =
                new ChunkReduceNode("reduce-1", "chunk-1", List.of("n1", "n2")).execute(context);

        ChunkSummary merged = result.summary();
        assertNotNull(merged);
        assertTrue(merged.isComplete());
        assertEquals(2, merged.changedSymbols().size(), "符号取并集");
        assertTrue(merged.changedSymbols().contains("RefundClient#send(long, int)"));
        assertEquals("票据号改成 long", merged.intent(), "intent 取第一个非空");
        assertEquals(2, merged.risks().size());
        assertTrue(merged.crossChunkQuestions().contains("调用方是否已同步改为 long？"));
        assertFalse(result.hasDegradation());
    }

    @Test
    void recordsDegradationWhenMergedSummaryIncomplete() throws DagExecutionException {
        // 所有维度的摘要都降级 → 合并后不可用，必须记入 degradations，
        // 否则 GlobalReduce 会因为摘要为空而静默失效
        NodeContext context = NodeContext.initial("task-1")
                .withCompletedResult("n1",
                        AgentReview.success(List.of(), ChunkSummary.degraded("chunk-1", "模型没给 summary"), 1))
                .withCompletedResult("n2",
                        AgentReview.success(List.of(), ChunkSummary.degraded("chunk-1", "summary 字段缺失"), 1));

        ChunkReduceResult result =
                new ChunkReduceNode("reduce-1", "chunk-1", List.of("n1", "n2")).execute(context);

        assertNotNull(result.summary());
        assertTrue(result.summary().degraded());
        assertFalse(result.summary().isComplete());
        assertTrue(result.hasDegradation());
        assertTrue(result.degradations().stream().anyMatch(d -> d.contains("摘要不完整")),
                "摘要不完整要显式记录，实际: " + result.degradations());
    }

    @Test
    void summaryIsNullWhenNoDimensionProducedOne() throws DagExecutionException {
        // M5 路径：Agent 不产出摘要
        NodeContext context = NodeContext.initial("task-1")
                .withCompletedResult("n1", AgentReview.success(List.of(), 1))
                .withCompletedResult("n2", AgentReview.success(List.of(), 1));

        ChunkReduceResult result =
                new ChunkReduceNode("reduce-1", "chunk-1", List.of("n1", "n2")).execute(context);

        assertFalse(result.hasSummary());
        assertFalse(result.hasDegradation(), "没有摘要不等于降级（M5 行为）");
    }
}
