package com.codemind.review.application;

import com.codemind.review.domain.changemap.ChangeMap;
import com.codemind.review.domain.chunk.ReviewChunk;
import com.codemind.review.domain.dag.CrossSessionReviewResult;
import com.codemind.review.domain.dag.DagOrchestrator;
import com.codemind.review.domain.dag.Node;
import com.codemind.review.domain.dag.NodeContext;
import com.codemind.review.domain.finding.Finding;
import com.codemind.review.domain.rule.RuleRecallPort;
import com.codemind.review.domain.task.ReviewTask;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.client.RestTemplate;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * ROADMAP M11 验证点：上传规范文档后，评审意见能引用对应条款。
 *
 * <p>固定资产样例：{@code src/test/resources/rule/sample-convention.md}（含 §3.2.1 方法命名规则）+
 * 一个违反该规则的 diff 样例（新增单字母命名方法）。真实走 {@code POST /internal/rules/documents} →
 * 容器里装配好的 {@link DagOrchestrator}（真的含 {@link RuleRecallPort}，经 Feign 真实调用
 * rule-service）→ {@code executeCrossSessionReview}（真实跑 {@code RecallRulesNode} →
 * {@code ReviewChunkNode} × 4 维度 → {@code ChunkReduceNode}，4 个维度都用真实 LLM）→
 * 断言返回的 {@code Finding} 列表至少一条 {@code message} 包含 sectionPath {@code "3.2.1"}。
 *
 * <p>S6 规格轴发现：早期版本第 3 步直接手工调用 {@code ReviewAgent.review(chunk, null, rules)}，
 * 绕过了 {@code RecallRulesNode}/{@code ReviewChunkNode}/{@code DagOrchestrator} 整条生产链路——
 * ROADMAP 验证点从未在真实生产路径上端到端验证过（账本 Task 15 记录的真实 bug，即
 * {@code ReviewDagConfig} 漏传 {@code RuleRecallPort} 导致 {@code RecallRulesNode} 永不创建，
 * 正是这类缺口的例子，当时只被一条反射断言抓到）。现改为走
 * {@code DagOrchestrator.executeCrossSessionReview}，ChangeMap/切片阶段用确定性 stub 节点
 * （不需要真实 LLM，和 M11 验证点无关），4 个评审维度与全局归约仍用容器里装配好的真实 LlmClient。
 *
 * <p>前置（三者齐备才跑，否则 {@code assumeTrue} 跳过，同既有真实 LLM IT 口径——
 * 跳过时该里程碑不能标完成，但测试本身不因缺前置而报失败）：
 * <pre>
 * cd deploy &amp;&amp; docker compose up -d
 * mvn -q package -DskipTests -pl codemind-rule -am
 * cp codemind-rule/target/codemind-rule-0.1.0-SNAPSHOT.jar /tmp/codemind-rule-run.jar
 * java -jar /tmp/codemind-rule-run.jar &amp;   # 本 IT 关了 discovery，Feign 直连 127.0.0.1:8084
 * export CODEMIND_LLM_API_KEY=&lt;中转端点 key&gt;
 * </pre>
 */
@SpringBootTest
@TestPropertySource(properties = {
        "spring.cloud.nacos.discovery.enabled=false",
        // 关了 discovery，Feign 直连本机 rule-service（须先启动它，见类注释前置条件）
        "codemind.rule.url=${CODEMIND_RULE_URL:http://127.0.0.1:8084}"
})
class RuleRecallAndCiteIT {

    @org.springframework.test.context.bean.override.convention.TestBean(name = "rocketMqDomainEventPublisher", methodName = "publicationTestPublisher")
    private com.codemind.review.domain.event.DomainEventPublisher publicationTestPublisher;

    static com.codemind.review.domain.event.DomainEventPublisher publicationTestPublisher() {
        return new com.codemind.review.support.RecordingDomainEventPublisher();
    }

    // 违反 §3.2.1「禁止单字母方法名」的 diff：新增方法 x()
    private static final String DIFF = """
            diff --git a/OrderService.java b/OrderService.java
            index 1111111..2222222 100644
            --- a/OrderService.java
            +++ b/OrderService.java
            @@ -10,2 +10,6 @@ public class OrderService {
            +    public int x(int a, int b) {
            +        return a + b;
            +    }
            """;

    private final RestTemplate rest = new RestTemplate();

    @Autowired
    private DagOrchestrator dagOrchestrator;

    @Test
    void realLlmCitesSectionPathWhenRuleIsViolated() throws Exception {
        assumeTrue(hasRealKey(), "未设置真实 CODEMIND_LLM_API_KEY，跳过真实 LLM 调用");
        assumeTrue(ruleServiceReachable(), "rule-service 未在本机 8084 运行，跳过真实验收（见类注释前置条件）");

        String project = "codemind/rule-recall-it-" + UUID.randomUUID();
        String markdown = resource("rule/sample-convention.md");

        // 1. 真实上传样例规范文档（走 HTTP，rule-service 独立进程 + 真实 PG）
        ResponseEntity<String> uploadResponse = rest.postForEntity(
                "http://127.0.0.1:8084/internal/rules/documents",
                Map.of("project", project, "title", "示例团队规范", "content", markdown),
                String.class);
        assertTrue(uploadResponse.getStatusCode().is2xxSuccessful(),
                "上传规范文档应成功，实际: " + uploadResponse.getStatusCode());

        // 2. 真实走生产 DAG 链路：BuildChangeMap（stub，不碰真实 LLM，与本验证点无关）→ PlanChunks
        // （stub，直接返回预设 chunk）→ RecallRulesNode（真经 Feign 调 rule-service）→
        // ReviewChunkNode × 4 维度（真实 LLM）→ ChunkReduceNode → GlobalReduce（真实 LLM，容器已装配）
        ReviewChunk chunk = new ReviewChunk(
                "chunk-order", List.of("OrderService.java"), List.of("OrderService#x(int,int)"),
                DIFF, 120, false);
        Node changeMapNode = new StubNode("build-change-map",
                ChangeMap.of("intent", List.of(), List.of(), List.of(), List.of()));
        Node planNode = new StubNode("plan-chunks", List.of(chunk));
        ReviewTask task = ReviewTask.create(project, 1, "head-1", "base-1", "evt-" + UUID.randomUUID());

        CrossSessionReviewResult result = dagOrchestrator.executeCrossSessionReview(
                changeMapNode, planNode, task, NodeContext.initial("task-" + UUID.randomUUID()));

        List<Finding> findings = result.allFindings();
        boolean citesSectionPath = findings.stream().anyMatch(f -> f.message().contains("3.2.1"));
        assertTrue(citesSectionPath,
                "至少一条 Finding 的 message 应引用 sectionPath 3.2.1，实际 messages: "
                        + findings.stream().map(Finding::message).toList());
        System.out.println("[RuleRecallAndCiteIT] 真实生产 DAG 链路返回 " + findings.size() + " 条 Finding");
    }

    /** Stub 节点：直接返回预设结果，不实际计算（ChangeMap/切片阶段与本验证点无关）。 */
    private record StubNode(String id, Object result) implements Node {
        @Override
        public Object execute(NodeContext context) {
            return result;
        }
    }

    private boolean ruleServiceReachable() {
        try {
            ResponseEntity<String> health = rest.getForEntity("http://127.0.0.1:8084/actuator/health", String.class);
            return health.getStatusCode().is2xxSuccessful();
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static boolean hasRealKey() {
        String key = System.getenv("CODEMIND_LLM_API_KEY");
        return key != null && !key.isBlank() && !key.equals("placeholder-no-key");
    }

    private static String resource(String path) {
        try (InputStream in = RuleRecallAndCiteIT.class.getClassLoader().getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalStateException("测试资源未找到: " + path);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
