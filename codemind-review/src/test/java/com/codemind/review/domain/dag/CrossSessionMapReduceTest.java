package com.codemind.review.domain.dag;

import com.codemind.review.domain.agent.*;
import com.codemind.review.domain.changemap.ChangeMap;
import com.codemind.review.domain.changemap.ChangeMapAgent;
import com.codemind.review.domain.changemap.ChangeMapJsonParser;
import com.codemind.review.domain.chunk.*;
import com.codemind.review.domain.finding.Category;
import com.codemind.review.domain.finding.Finding;
import com.codemind.review.domain.task.ReviewTask;
import com.codemind.review.domain.task.ReviewTaskRepository;
import com.codemind.review.domain.task.ReviewTaskStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 跨会话 Map-Reduce 全流程单测（M6）：真实 {@link SemanticChunker} + 真实 4 文件 diff +
 * 小 diff 预算，跑完 BuildChangeMap → PlanChunks → 4N 个子会话 → N 个 ChunkReduce → GlobalReduce。
 *
 * <p>LLM 用 {@link ScriptedLlmClient}：按 prompt 内容作答（不是死字符串），于是
 * 「GlobalReduce 能发现跨文件签名不一致」这条断言证明的是信息真的流到了归约会话。
 *
 * <p>里程碑验证点本体见
 * {@link #callerAndCalleeLandInDifferentChunksAndGlobalReduceCatchesMismatch()}：
 * 调用方与定义方被真实切片器分到不同片，单片都看不到对面，只有 GlobalReduce 能发现签名不一致。
 *
 * <p>这条能成立依赖 M6 补上的「聚合体超预算时沿合并缝拆分」（ARCHITECTURE 6.3 降级链最后一步）：
 * 在它之前，M2 的 {@code mergeByCallRelationship} 会把「diff 文本里出现了对方方法名」的文件
 * 永久焊进同一个 unit，且装箱从不拆 unit，跨片场景根本不会出现。
 */
class CrossSessionMapReduceTest {

    private InMemoryNodeRunRepository nodeRuns;
    private DagScheduler scheduler;
    private ScriptedLlmClient llm;
    private Map<ReviewDimension, ReviewAgent> agents;
    private StubChunkRepository chunkRepository;
    private StubTaskRepository taskRepository;

    @BeforeEach
    void setUp() {
        nodeRuns = new InMemoryNodeRunRepository();
        scheduler = new DagScheduler(nodeRuns, Duration.ofSeconds(20), 0);
        llm = new ScriptedLlmClient();
        FindingJsonParser findingParser = new FindingJsonParser();
        ChunkSummaryJsonParser summaryParser = new ChunkSummaryJsonParser();
        agents = new EnumMap<>(ReviewDimension.class);
        for (ReviewDimension dim : ReviewDimension.values()) {
            agents.put(dim, new ReviewAgent(dim, llm, findingParser, summaryParser, 0));
        }
        chunkRepository = new StubChunkRepository();
        taskRepository = new StubTaskRepository();
    }

    private DagOrchestrator orchestrator() {
        return new DagOrchestrator(scheduler, agents,
                new GlobalReduceAgent(llm, new FindingJsonParser(), 0), taskRepository);
    }

    private NodeContext contextWithRealDiff(String taskId) {
        return NodeContext.initial(taskId)
                .withSharedState("diffText", resource("diff/cross-signature.diff"))
                .withSharedState("newSources", Map.of(
                        "PaymentGateway.java", resource("diff/src/PaymentGateway.java"),
                        "RefundClient.java", resource("diff/src/RefundClient.java"),
                        "AuditLogger.java", resource("diff/src/AuditLogger.java"),
                        "NotificationSender.java", resource("diff/src/NotificationSender.java")));
    }

    private BuildChangeMapNode changeMapNode() {
        return new BuildChangeMapNode("build-change-map",
                new ChangeMapAgent(llm, new ChangeMapJsonParser(), 0));
    }

    /** 小 diff 预算：让 4 文件 diff 真的被切成多片，而不是靠「应该会拆」的口头假设。 */
    private PlanChunksNode planNode(int diffBudget) {
        return new PlanChunksNode("plan-chunks", new SemanticChunker(new JtokkitTokenEstimator()),
                chunkRepository, taskRepository, ChunkBudget.ofDiffBudget(diffBudget));
    }

    @Test
    void overBudgetPrFansOutIntoMultipleSessionsAndReduces() throws Exception {
        ReviewTask task = ReviewTask.create("codemind/demo", 7, "head-1", "base-1", "evt-1");

        CrossSessionReviewResult result = orchestrator().executeCrossSessionReview(
                changeMapNode(), planNode(150), task, contextWithRealDiff("task-m6-fanout"));

        // 真实切片器 + 小预算 → 真的多片（不是构造的 chunk）
        assertThat(result.chunkResults()).hasSizeGreaterThan(1);

        // 每片 × 4 维度都真的跑了评审节点
        for (String chunkId : result.chunkResults().keySet()) {
            for (ReviewDimension dim : ReviewDimension.values()) {
                String nodeId = "review-" + chunkId + "-" + dim.name().toLowerCase();
                assertThat(nodeRuns.findByTaskAndNode("task-m6-fanout", nodeId))
                        .as("子会话节点 %s 应已执行", nodeId)
                        .isPresent();
            }
            assertThat(nodeRuns.findByTaskAndNode("task-m6-fanout", "reduce-" + chunkId)).isPresent();
        }
        assertThat(nodeRuns.findByTaskAndNode("task-m6-fanout", "build-change-map")).isPresent();
        assertThat(nodeRuns.findByTaskAndNode("task-m6-fanout", "global-reduce")).isPresent();

        // 每片都拿到了可用摘要
        assertThat(result.chunkResults().values())
                .allSatisfy(r -> assertThat(r.summary()).isNotNull());
        assertThat(result.chunkResults().values())
                .allSatisfy(r -> assertThat(r.summary().isComplete()).isTrue());
    }

    @Test
    void changeMapCarriesAstSignatureChangeAndReachesEverySubSession() throws Exception {
        CrossSessionReviewResult result = orchestrator().executeCrossSessionReview(
                changeMapNode(), planNode(150), null, contextWithRealDiff("task-m6-map"));

        ChangeMap map = result.changeMap();
        assertThat(map).isNotNull();
        assertThat(map.files()).hasSize(4);
        assertThat(map.signatureChangesOnly()).hasSize(1);
        assertThat(map.signatureChangesOnly().get(0).signature())
                .isEqualTo("RefundClient#submitRefund(long, int)");

        // 每个子会话的 prompt 里都注入了变更地图
        List<String> reviewPrompts = llm.promptsContaining("unified diff:");
        assertThat(reviewPrompts).isNotEmpty();
        assertThat(reviewPrompts).allSatisfy(p -> assertThat(p)
                .contains("【全局变更地图】")
                .contains("RefundClient#submitRefund(String, int)"));
    }

    @Test
    void globalReduceSeesOnlySummariesNoRawDiff() throws Exception {
        CrossSessionReviewResult result = orchestrator().executeCrossSessionReview(
                changeMapNode(), planNode(150), null, contextWithRealDiff("task-m6-isolation"));

        String globalPrompt = llm.promptsContaining("【各切片摘要】").get(0);
        assertThat(globalPrompt)
                .doesNotContain("diff --git")
                .doesNotContain("@@ -");
        assertThat(globalPrompt).contains("RefundClient#submitRefund(long, int)");

        // 各片摘要（而不只是变更地图）确实进了归约 prompt：每片都有自己的小节和上交的问题
        assertThat(result.chunkResults().keySet())
                .allSatisfy(chunkId -> assertThat(globalPrompt).contains("切片 " + chunkId));
        assertThat(globalPrompt)
                .contains("待回答的跨切片问题")
                .contains("其它片是否调用了本片变更的方法？");
    }

    /**
     * <b>M6 里程碑验证点本体</b>：超预算 PR → 调用方与定义方被切到不同片 →
     * 单片评审都看不到对面 → GlobalReduce 只凭各片摘要发现跨文件签名不一致。
     *
     * <p>这条之所以可达，靠的是「聚合体超预算时沿合并缝拆分」（ARCHITECTURE 6.3 降级链最后一步）：
     * 没有它，M2 的调用关系合并会把调用方和定义方永久焊在同一片，跨片场景根本不会出现。
     */
    @Test
    void callerAndCalleeLandInDifferentChunksAndGlobalReduceCatchesMismatch() throws Exception {
        CrossSessionReviewResult result = orchestrator().executeCrossSessionReview(
                changeMapNode(), planNode(150), null, contextWithRealDiff("task-m6-verifypoint"));

        // 1. 真实切片器把调用方与定义方分到了不同片
        String callerChunk = chunkIdContaining(result, "PaymentGateway.java");
        String calleeChunk = chunkIdContaining(result, "RefundClient.java");
        assertThat(callerChunk)
                .as("调用方与定义方必须分落不同片，否则本用例验不到跨会话归约")
                .isNotEqualTo(calleeChunk);

        // 2. 两片各自都只看到自己那一侧
        assertThat(result.chunkResults().get(calleeChunk).summary().changedSymbols())
                .contains("RefundClient#submitRefund(long, int)");
        assertThat(result.chunkResults().get(callerChunk).summary().changedSymbols())
                .noneSatisfy(s -> assertThat(s).contains("submitRefund"));

        // 3. 任何单片的评审 prompt 都不同时含两侧的定义（否则「只有 GlobalReduce 能发现」不成立）
        for (String prompt : llm.promptsContaining("unified diff:")) {
            String diffSection = prompt.substring(prompt.indexOf("unified diff:"));
            boolean hasCalleeDecl = diffSection.contains("public void submitRefund(long");
            boolean hasCallerCall = diffSection.contains("submitRefund(\"TICKET-");
            assertThat(hasCalleeDecl && hasCallerCall)
                    .as("单片不该同时看到定义与调用，否则片内就能发现，用不上全局归约")
                    .isFalse();
        }

        // 4. GlobalReduce 只凭摘要发现了签名不一致
        assertThat(result.globalReduce().degraded()).isFalse();
        assertThat(result.globalReduce().findings()).hasSize(1);
        Finding crossFile = result.globalReduce().findings().get(0);
        assertThat(crossFile.file()).isEqualTo("RefundClient.java");
        assertThat(crossFile.category()).isEqualTo(Category.CORRECTNESS);
        assertThat(crossFile.message()).containsAnyOf("signature", "type", "签名", "类型");
    }

    private static String chunkIdContaining(CrossSessionReviewResult result, String file) {
        return result.chunkResults().values().stream()
                .filter(r -> r.summary() != null)
                .map(ChunkReduceResult::chunkId)
                .filter(id -> result.chunkResults().get(id).summary().changedSymbols().stream()
                        .anyMatch(s -> s.startsWith(file.replace(".java", "") + "#")))
                .findFirst()
                .orElseThrow(() -> new AssertionError("找不到含 " + file + " 的切片"));
    }

    @Test
    void crossFileSignatureFindingIsAppendedToTaskFindings() throws Exception {
        ReviewTask task = ReviewTask.create("codemind/demo", 8, "head-2", "base-2", "evt-2");

        CrossSessionReviewResult result = orchestrator().executeCrossSessionReview(
                changeMapNode(), planNode(150), task, contextWithRealDiff("task-m6-findings"));

        // GlobalReduce 的跨文件意见
        assertThat(result.globalReduce().degraded()).isFalse();
        List<Finding> crossFile = result.globalReduce().findings();
        assertThat(crossFile).hasSize(1);
        assertThat(crossFile.get(0).file()).isEqualTo("RefundClient.java");
        assertThat(crossFile.get(0).category()).isEqualTo(Category.CORRECTNESS);
        assertThat(crossFile.get(0).message()).containsAnyOf("signature", "type", "签名", "类型");

        // 直接追加到任务级 Findings（不额外加 MergeFindings 节点）
        assertThat(task.findings()).containsAll(crossFile);
        assertThat(task.findings()).isEqualTo(result.allFindings());
        assertThat(task.changeMap()).isNotNull();
        assertThat(task.chunkSummaries()).hasSameSizeAs(result.chunkResults());
    }

    @Test
    void stateAdvancesThroughPlanningReviewingAggregating() throws Exception {
        ReviewTask task = ReviewTask.create("codemind/demo", 9, "head-3", "base-3", "evt-3");
        assertThat(task.status()).isEqualTo(ReviewTaskStatus.CREATED);

        orchestrator().executeCrossSessionReview(
                changeMapNode(), planNode(150), task, contextWithRealDiff("task-m6-state"));

        // PUBLISHED 的入口是回写 PR，属 M7
        assertThat(task.status()).isEqualTo(ReviewTaskStatus.AGGREGATING);
        assertThat(taskRepository.statusHistory).containsExactly(
                ReviewTaskStatus.PLANNING, ReviewTaskStatus.REVIEWING, ReviewTaskStatus.AGGREGATING);
    }

    @Test
    void globalReduceDegradationDoesNotLoseChunkFindings() throws Exception {
        llm.breakGlobalReduce = true;
        ReviewTask task = ReviewTask.create("codemind/demo", 10, "head-4", "base-4", "evt-4");

        CrossSessionReviewResult result = orchestrator().executeCrossSessionReview(
                changeMapNode(), planNode(150), task, contextWithRealDiff("task-m6-degrade"));

        assertThat(result.globalReduce().degraded()).isTrue();
        assertThat(result.allDegradations()).anySatisfy(d -> assertThat(d).contains("全局归约降级"));
        // 片级意见照常保留
        assertThat(result.allFindings()).isNotEmpty();
        assertThat(task.status()).isEqualTo(ReviewTaskStatus.AGGREGATING);
    }

    private static String resource(String path) {
        try (InputStream in = CrossSessionMapReduceTest.class.getClassLoader().getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalArgumentException("找不到测试资源: " + path);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * 按 prompt 内容作答的假 LLM，三类会话各自应答：
     * <ul>
     *   <li>ChangeMap 会话（prompt 含「方法签名变更（AST 提取」）→ 返回 intent/modules/highRiskFiles；</li>
     *   <li>子会话（prompt 含「unified diff:」）→ 返回 findings + summary，summary 的 changedSymbols
     *       从 prompt 的「变更符号:」行原样回抄（模拟一个老实的模型，参数类型因此带着）；</li>
     *   <li>GlobalReduce（prompt 含「【各切片摘要】」）→ 只有在摘要里同时看到新签名 long 与
     *       旧签名 String 时才报 CORRECTNESS。</li>
     * </ul>
     */
    private static final class ScriptedLlmClient implements LlmClient {

        private final List<String> prompts = Collections.synchronizedList(new ArrayList<>());
        private volatile boolean breakGlobalReduce = false;

        @Override
        public String complete(String systemPrompt, String userPrompt) {
            prompts.add(userPrompt);

            if (userPrompt.contains("【各切片摘要】")) {
                if (breakGlobalReduce) {
                    return "我需要看到源码才能判断";
                }
                // 只认「来自摘要」的证据：定义方那片声明了新签名，且另有一片上交了字符串实参的调用点。
                // 两者分别来自不同切片的 summary——变更地图单独给不出第二条。
                boolean calleeSummaryDeclaresLong =
                        userPrompt.contains("变更符号: RefundClient#submitRefund(long, int)");
                boolean callerSummaryReportsStringArg =
                        userPrompt.contains("本片调用了 submitRefund(\"TICKET-\"");
                if (calleeSummaryDeclaresLong && callerSummaryReportsStringArg) {
                    return """
                            {"findings": [
                              {"file": "RefundClient.java", "line": 0, "severity": "BLOCKER",
                               "category": "CORRECTNESS",
                               "message": "signature mismatch: submitRefund 首参由 String 改为 long，调用方未同步",
                               "suggestion": "调用方改传 long",
                               "codeSnippet": "RefundClient#submitRefund(long, int)"}
                            ]}
                            """;
                }
                return "{\"findings\": []}";
            }

            if (userPrompt.contains("方法签名变更（AST 提取")) {
                return """
                        {"intent": "退款票据号类型从 String 改为 long",
                         "modules": ["demo"],
                         "highRiskFiles": ["RefundClient.java"]}
                        """;
            }

            // 子会话：把 prompt 里的变更符号抄进 summary
            String symbols = extractSymbols(userPrompt);
            return """
                    {"findings": [
                      {"file": "AuditLogger.java", "line": 12, "severity": "MINOR", "category": "STYLE",
                       "message": "魔法数字 1000 应提取为常量", "suggestion": "定义 MAX_ENTRIES",
                       "codeSnippet": "entries.size() > 1000"}
                    ],
                     "summary": {
                       "changedSymbols": [%s],
                       "intent": "本片改动与退款流程相关",
                       "risks": ["跨文件调用可能未同步"],
                       "crossChunkQuestions": [%s]
                     }}
                    """.formatted(symbols, crossChunkQuestions(userPrompt, symbols));
        }

        /**
         * 模拟一个遵守 prompt 约束的子会话：只有「本片调用了某方法、但本片并不定义它」时，
         * 才把调用点原样上交给全局归约；否则只提一句泛问。
         */
        private String crossChunkQuestions(String userPrompt, String symbolsJson) {
            boolean callsSubmitRefund = userPrompt.contains("submitRefund(\"");
            boolean definesSubmitRefund = symbolsJson.contains("submitRefund");
            if (callsSubmitRefund && !definesSubmitRefund) {
                return "\"本片调用了 submitRefund(\\\"TICKET-\\\" + orderId, amountCents)，"
                        + "但定义不在本片，第一个实参是字符串，签名对得上吗？\"";
            }
            return "\"其它片是否调用了本片变更的方法？\"";
        }

        /** 从子会话 prompt 的「变更符号: a, b」行取出符号并转成 JSON 字符串数组元素。 */
        private String extractSymbols(String userPrompt) {
            for (String line : userPrompt.split("\n")) {
                if (line.startsWith("变更符号: ")) {
                    String value = line.substring("变更符号: ".length()).trim();
                    if (value.isEmpty() || value.equals("（无）")) {
                        return "\"（无符号）\"";
                    }
                    StringJoiner joiner = new StringJoiner(", ");
                    // 按顶层逗号切：签名自带参数逗号（submitRefund(long, int)），不能直接 split(",")
                    for (String symbol : splitTopLevel(value)) {
                        if (!symbol.isBlank()) {
                            joiner.add('"' + symbol.trim() + '"');
                        }
                    }
                    return joiner.toString();
                }
            }
            return "\"（无符号）\"";
        }

        /** 按括号外的逗号切分，保住 {@code foo(long, int)} 这类带参数逗号的签名。 */
        private static List<String> splitTopLevel(String s) {
            List<String> parts = new ArrayList<>();
            int depth = 0;
            StringBuilder current = new StringBuilder();
            for (char ch : s.toCharArray()) {
                if (ch == '(' || ch == '<') {
                    depth++;
                } else if (ch == ')' || ch == '>') {
                    depth--;
                }
                if (ch == ',' && depth == 0) {
                    parts.add(current.toString());
                    current.setLength(0);
                } else {
                    current.append(ch);
                }
            }
            parts.add(current.toString());
            return parts;
        }

        List<String> promptsContaining(String marker) {
            synchronized (prompts) {
                return prompts.stream().filter(p -> p.contains(marker)).toList();
            }
        }
    }

    private static final class StubChunkRepository implements ChunkRepository {
        private final Map<String, List<ReviewChunk>> saved = new ConcurrentHashMap<>();

        @Override
        public void saveAll(String taskId, List<ReviewChunk> chunks) {
            saved.put(taskId, List.copyOf(chunks));
        }

        @Override
        public List<ReviewChunk> findByTask(String taskId) {
            return saved.getOrDefault(taskId, List.of());
        }
    }

    private static final class StubTaskRepository implements ReviewTaskRepository {
        private final List<ReviewTaskStatus> statusHistory = Collections.synchronizedList(new ArrayList<>());

        @Override
        public boolean insertIfAbsent(ReviewTask task) {
            return true;
        }

        @Override
        public Optional<ReviewTask> findByPrHead(String repo, int prNumber, String headSha) {
            return Optional.empty();
        }

        @Override
        public void updateStatus(String taskId, ReviewTaskStatus status) {
            statusHistory.add(status);
        }
    }

    private static final class InMemoryNodeRunRepository implements NodeRunRepository {
        private final Map<String, NodeRun> store = new ConcurrentHashMap<>();

        @Override
        public void save(NodeRun nodeRun) {
            store.put(nodeRun.taskId() + ":" + nodeRun.nodeId(), nodeRun);
        }

        @Override
        public Optional<NodeRun> findByTaskAndNode(String taskId, String nodeId) {
            return Optional.ofNullable(store.get(taskId + ":" + nodeId));
        }

        @Override
        public List<NodeRun> findCompletedByTask(String taskId) {
            return store.values().stream()
                    .filter(r -> r.taskId().equals(taskId) && r.isCompleted())
                    .toList();
        }
    }
}
