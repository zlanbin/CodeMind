package com.codemind.review.application;

import com.codemind.review.domain.agent.*;
import com.codemind.review.domain.changemap.ChangeMapAgent;
import com.codemind.review.domain.changemap.ChangeMapJsonParser;
import com.codemind.review.domain.chunk.*;
import com.codemind.review.domain.dag.*;
import com.codemind.review.domain.finding.FindingFilter;
import com.codemind.review.domain.memory.MemoryRecallException;
import com.codemind.review.domain.memory.MemoryRecallPort;
import com.codemind.review.domain.memory.RecalledMemory;
import com.codemind.review.domain.scm.ScmContentException;
import com.codemind.review.domain.scm.ScmContentPort;
import com.codemind.review.domain.task.ReviewTask;
import com.codemind.review.domain.task.ReviewTaskRepository;
import com.codemind.review.domain.task.ReviewTaskStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link ReviewExecutionService} 纯单测：入口链路的取内容规则与失败处理。
 *
 * <p>重点是「别去拉不该拉的文件」——删除文件、重命名的旧路径、非 Java 文件
 * 拉下去都是 404 或白费调用（计划评审重点 1~3）。
 *
 * <p>{@link DagOrchestrator} 是 final 类，用真实实例配桩 {@link LlmClient}；
 * {@code CrossSessionMapReduceTest} 里的同名桩是私有嵌套类，不可复用。
 */
class ReviewExecutionServiceTest {

    private static final String REPO = "owner/repo";
    private static final String HEAD = "headsha123";
    private static final String BASE = "basesha456";

    private final RecordingScmContentPort scm = new RecordingScmContentPort();
    private final StubTaskRepository tasks = new StubTaskRepository();
    private final ReviewOutboxDispatcherTest.MemoryOutbox requests = new ReviewOutboxDispatcherTest.MemoryOutbox();

    @Test
    void skipsJavaDeletedFilesWhenCollectingSourcePaths() {
        List<String> paths = ReviewExecutionService.sourcePathsOf(List.of(
                diffFile("Kept.java", "Kept.java", ChangeType.MODIFIED),
                diffFile("Gone.java", "Gone.java", ChangeType.DELETED)));

        assertEquals(List.of("Kept.java"), paths, "删除的文件没有新侧，不能去拉");
    }

    /**
     * 走真实 {@link DiffParser}：只验 sourcePathsOf 不够，要证明解析器从 GitHub 的重命名 diff
     * 里取出的就是新路径（S6 评审：原用例手工构造 DiffFile，绕过了解析）。
     * 含纯重命名（similarity 100%，无 hunk、无 ---/+++ 头）——它只有 rename from/to 两行可依。
     */
    @Test
    void usesNewPathForRenamedFiles() {
        String diff = """
                diff --git a/src/Old.java b/src/New.java
                similarity index 90%
                rename from src/Old.java
                rename to src/New.java
                index 1111111..2222222 100644
                --- a/src/Old.java
                +++ b/src/New.java
                @@ -1,1 +1,1 @@
                -class Old {}
                +class New {}
                diff --git a/src/Moved.java b/pkg/Moved.java
                similarity index 100%
                rename from src/Moved.java
                rename to pkg/Moved.java
                """;

        List<String> paths = ReviewExecutionService.sourcePathsOf(new DiffParser().parse(diff));

        assertEquals(List.of("src/New.java", "pkg/Moved.java"), paths, "重命名要用 newPath，旧路径已不存在");
    }

    @Test
    void ignoresNonJavaFiles() {
        List<String> paths = ReviewExecutionService.sourcePathsOf(List.of(
                diffFile("README.md", "README.md", ChangeType.MODIFIED),
                diffFile("config.yml", "config.yml", ChangeType.MODIFIED),
                diffFile("A.java", "A.java", ChangeType.ADDED)));

        assertEquals(List.of("A.java"), paths, "只有 Java 文件需要 AST 源码");
    }

    @Test
    void pullRequestWithoutJavaFilesStillProducesChunks() throws Exception {
        ReviewTask task = insertedTask();
        scm.diff = """
                diff --git a/README.md b/README.md
                --- a/README.md
                +++ b/README.md
                @@ -1,1 +1,1 @@
                -old doc
                +new doc
                """;

        CrossSessionReviewResult result = service().executeReview(task.taskId());

        assertNotNull(result);
        assertFalse(result.chunkResults().isEmpty(), "纯文档 PR 也要出片（降级为 hunk 切片）");
        assertEquals(List.of(), scm.requestedPaths, "没有 Java 文件就不该请求任何源码");
    }

    @Test
    void fetchesSourcesAtHeadSha() throws Exception {
        ReviewTask task = insertedTask();
        scm.diff = javaDiff();
        scm.sources = Map.of("A.java", "class A { void go() {} }");

        service().executeReview(task.taskId());

        assertEquals(HEAD, scm.requestedRef, "源码要取 headSha 上的版本，不是 baseSha");
    }

    @Test
    void alreadyRunningTaskIsSkipped() throws Exception {
        ReviewTask task = insertedTask();
        task.transitionTo(ReviewTaskStatus.PLANNING);
        task.transitionTo(ReviewTaskStatus.REVIEWING);
        tasks.store.put(task.taskId(), task);

        CrossSessionReviewResult result = service().executeReview(task.taskId());

        assertNull(result, "已在跑的任务不重复执行");
        assertNull(scm.requestedRef, "跳过的任务不该产生任何取内容调用");
    }

    @Test
    void scmFailureMarksTaskFailed() {
        ReviewTask task = insertedTask();
        scm.failure = new ScmContentException("上游挂了", new RuntimeException("boom"));

        ReviewExecutionService service = service();
        assertThrows(IllegalStateException.class, () -> service.executeReview(task.taskId()));

        assertTrue(tasks.statusHistory.contains(ReviewTaskStatus.FAILED),
                "取内容失败要把任务标 FAILED，不能静默停在 CREATED");
    }

    @Test
    void executionPersistsRenderedRequestAfterDag() {
        ReviewTask task = insertedTask();
        scm.diff = javaDiff();
        service().executeReview(task.taskId());
        var event = requests.findByTaskId(task.taskId()).orElseThrow().event();
        assertEquals(HEAD, event.headSha());
        assertEquals(REPO, event.repo());
        assertEquals(7, event.prNumber());
        assertTrue(event.summaryBody().contains("原始意见总数"));
        assertNotNull(task.report());
        assertEquals(ReviewTaskStatus.AGGREGATING, task.status());
    }

    @Test
    void emptyDiffStillRegistersCompletedRequest() {
        ReviewTask task = insertedTask();
        service().executeReview(task.taskId());
        assertTrue(requests.findByTaskId(task.taskId()).isPresent());
        assertEquals(ReviewTaskStatus.AGGREGATING, task.status());
    }

    @Test
    void scmFailureDoesNotCreatePublicationRequest() {
        ReviewTask task = insertedTask();
        scm.failure = new ScmContentException("unavailable", new RuntimeException());
        assertThrows(IllegalStateException.class, () -> service().executeReview(task.taskId()));
        assertTrue(requests.events.isEmpty());
    }

    @Test
    void mqFailureDoesNotMarkTaskFailed() {
        ReviewTask task = insertedTask();
        scm.diff = javaDiff();
        service().executeReview(task.taskId());
        new ReviewOutboxDispatcher(requests, event -> { throw new IllegalStateException("unavailable"); },
                java.time.Clock.systemUTC(), 50, Duration.ofSeconds(120), Duration.ofSeconds(60))
                .dispatchTask(task.taskId());
        assertEquals(ReviewTaskStatus.AGGREGATING, task.status());
        assertFalse(tasks.statusHistory.contains(ReviewTaskStatus.FAILED));
        assertTrue(requests.findByTaskId(task.taskId()).isPresent());
    }

    @Test
    void findingFilterRemovesFilteredFindingsBeforeReport() throws Exception {
        ReviewTask task = insertedTask();
        scm.diff = javaDiff();
        scm.sources = Map.of("A.java", "class A { void go() { int x = 1; } }");
        FindingFilter filterAll = new FindingFilter(
                (project, query, topK, minScore) -> List.of(new RecalledMemory("某条否决规则", 0.8)),
                (sys, user) -> "{\"filtered\": true}", 0);

        service(filterAll, new OneFindingLlmClient()).executeReview(task.taskId());

        assertEquals(0, task.report().totalFindings(), "唯一一条 Finding 被判定为同类否决意见，应被过滤");
        assertTrue(task.report().degradations().isEmpty(), "过滤器本身未降级，不该产生降级说明");
    }

    @Test
    void findingFilterDegradationIsMergedIntoReport() throws Exception {
        ReviewTask task = insertedTask();
        scm.diff = javaDiff();
        scm.sources = Map.of("A.java", "class A { void go() { int x = 1; } }");
        FindingFilter degradingFilter = new FindingFilter(
                (project, query, topK, minScore) -> {
                    throw new MemoryRecallException("memory 不可达", null);
                },
                (sys, user) -> { throw new AssertionError("召回已失败，不该调用 LLM"); }, 0);

        service(degradingFilter, new OneFindingLlmClient()).executeReview(task.taskId());

        assertEquals(1, task.report().totalFindings(), "召回失败要放行 Finding，不能压掉");
        assertEquals(1, task.report().degradations().size(), "召回失败的降级说明要并入报告");
    }

    // --- 装配 ---

    private ReviewExecutionService service() {
        return service(passThroughFindingFilter(), new ScriptedLlmClient());
    }

    private ReviewExecutionService service(FindingFilter findingFilter, LlmClient llm) {
        ObjectMapper mapper = new ObjectMapper();
        FindingJsonParser findingParser = new FindingJsonParser(mapper);
        ChunkSummaryJsonParser summaryParser = new ChunkSummaryJsonParser(mapper);
        Map<ReviewDimension, ReviewAgent> agents = new EnumMap<>(ReviewDimension.class);
        for (ReviewDimension dim : ReviewDimension.values()) {
            agents.put(dim, new ReviewAgent(dim, llm, findingParser, summaryParser, 0));
        }
        DagScheduler scheduler =
                new DagScheduler(new InMemoryNodeRunRepository(), Duration.ofSeconds(20), 0);
        DagOrchestrator orchestrator = new DagOrchestrator(scheduler, agents,
                new GlobalReduceAgent(llm, findingParser, 0), tasks);
        return new ReviewExecutionService(scm, tasks, orchestrator,
                new ChangeMapAgent(llm, new ChangeMapJsonParser(mapper), 0),
                new SemanticChunker(new JtokkitTokenEstimator()),
                new StubChunkRepository(), ChunkBudget.ofDiffBudget(4000),
                org.mockito.Mockito.mock(java.util.concurrent.ExecutorService.class),
                new ReviewCompletionService(tasks, requests, new org.springframework.transaction.support.TransactionTemplate(
                        new org.springframework.transaction.support.AbstractPlatformTransactionManager() {
                            protected Object doGetTransaction() { return new Object(); }
                            protected void doBegin(Object t, org.springframework.transaction.TransactionDefinition d) { }
                            protected void doCommit(org.springframework.transaction.support.DefaultTransactionStatus s) { }
                            protected void doRollback(org.springframework.transaction.support.DefaultTransactionStatus s) { }
                        })),
                new com.codemind.review.domain.report.ReviewReportBuilder(),
                com.codemind.review.domain.report.ReportPolicy.defaults(), new ReviewMarkdownRenderer(),
                findingFilter);
    }

    /** 不召回任何候选，等价于「过滤器不生效」，让既有用例的断言不受影响。 */
    private static FindingFilter passThroughFindingFilter() {
        return new FindingFilter((project, query, topK, minScore) -> List.of(),
                (sys, user) -> "{\"filtered\": false}", 0);
    }

    private ReviewTask insertedTask() {
        ReviewTask task = ReviewTask.create(REPO, 7, HEAD, BASE, UUID.randomUUID().toString());
        tasks.store.put(task.taskId(), task);
        return task;
    }

    private static DiffFile diffFile(String oldPath, String newPath, ChangeType type) {
        return new DiffFile(oldPath, newPath, type,
                List.of(new DiffHunk(1, 1, 1, 1, List.of("-old", "+new"))));
    }

    private static String javaDiff() {
        return """
                diff --git a/A.java b/A.java
                --- a/A.java
                +++ b/A.java
                @@ -1,1 +1,1 @@
                -class A { void go() { int x = 1; } }
                +class A { void go() { int x = 2; } }
                """;
    }

    // --- 桩 ---

    /** 记录取内容的参数，可设失败。 */
    private static final class RecordingScmContentPort implements ScmContentPort {
        private String diff = "";
        private Map<String, String> sources = Map.of();
        private List<String> requestedPaths;
        private String requestedRef;
        private ScmContentException failure;

        @Override
        public String fetchDiff(String repo, int prNumber) {
            if (failure != null) {
                throw failure;
            }
            return diff;
        }

        @Override
        public Map<String, String> fetchNewSources(String repo, String ref, List<String> paths) {
            requestedRef = ref;
            requestedPaths = List.copyOf(paths);
            return sources;
        }

        @Override
        public com.codemind.review.domain.scm.CompareResult fetchCompareDiff(
                String repo, String oldHead, String newHead) {
            throw new UnsupportedOperationException("本测试类未覆盖 compareDiff，见 Task 3 的新用例");
        }
    }

    private static final class StubTaskRepository implements ReviewTaskRepository {
        private final Map<String, ReviewTask> store = new ConcurrentHashMap<>();
        private final List<ReviewTaskStatus> statusHistory =
                Collections.synchronizedList(new ArrayList<>());

        @Override
        public boolean insertIfAbsent(ReviewTask task) {
            return store.putIfAbsent(task.taskId(), task) == null;
        }

        @Override
        public Optional<ReviewTask> findByPrHead(String repo, int prNumber, String headSha) {
            return Optional.empty();
        }

        @Override
        public Optional<ReviewTask> findById(String taskId) {
            return Optional.ofNullable(store.get(taskId));
        }

        @Override
        public List<String> findStaleInProgress(Duration idleFor) {
            return List.of();
        }

        @Override
        public boolean claimForRecovery(String taskId) {
            return false;
        }

        @Override
        public Optional<ReviewTask> findByIdForUpdate(String taskId) {
            return findById(taskId);
        }

        @Override
        public boolean markFailedIfUnpublished(String taskId) {
            statusHistory.add(ReviewTaskStatus.FAILED);
            return true;
        }

        @Override
        public boolean markPublishedIfAggregating(String taskId) { return false; }

        @Override
        public void updateStatus(String taskId, ReviewTaskStatus status) {
            statusHistory.add(status);
        }
    }

    private static final class StubChunkRepository implements ChunkRepository {
        @Override
        public void saveAll(String taskId, List<ReviewChunk> chunks) {
        }

        @Override
        public List<ReviewChunk> findByTask(String taskId) {
            return List.of();
        }
    }

    private static final class InMemoryNodeRunRepository implements NodeRunRepository {
        private final Map<String, NodeRun> store = new ConcurrentHashMap<>();

        @Override
        public void save(NodeRun nodeRun) {
            store.put(nodeRun.taskId() + "#" + nodeRun.nodeId(), nodeRun);
        }

        @Override
        public Optional<NodeRun> findByTaskAndNode(String taskId, String nodeId) {
            return Optional.ofNullable(store.get(taskId + "#" + nodeId));
        }

        @Override
        public List<NodeRun> findCompletedByTask(String taskId) {
            return store.values().stream()
                    .filter(r -> r.taskId().equals(taskId))
                    .filter(r -> r.status() == NodeRunStatus.COMPLETED)
                    .toList();
        }
    }

    /** 最简桩：所有 LLM 调用都返回空 findings / 合法摘要，让链路能跑完。 */
    private static final class ScriptedLlmClient implements LlmClient {
        @Override
        public String complete(String systemPrompt, String userPrompt) {
            if (userPrompt.contains("【各切片摘要】")) {
                return "{\"findings\": []}";
            }
            if (userPrompt.contains("方法签名变更（AST 提取")) {
                return """
                        {"intent": "测试变更", "modules": ["test"], "highRiskFiles": []}
                        """;
            }
            return """
                    {"findings": [],
                     "summary": {"changedSymbols": ["A#go()"], "intent": "改了实现",
                                 "risks": [], "crossChunkQuestions": []}}
                    """;
        }
    }

    /**
     * 像 {@link ScriptedLlmClient} 一样让链路跑完，但第一次切片评审调用产出 1 条真实 Finding，
     * 供 Task 4 的过滤用例验证 {@code FindingFilter} 真的在 {@code reportBuilder.build} 之前生效。
     */
    private static final class OneFindingLlmClient implements LlmClient {
        private boolean findingEmitted = false;

        @Override
        public String complete(String systemPrompt, String userPrompt) {
            if (userPrompt.contains("【各切片摘要】")) {
                return "{\"findings\": []}";
            }
            if (userPrompt.contains("方法签名变更（AST 提取")) {
                return """
                        {"intent": "测试变更", "modules": ["test"], "highRiskFiles": []}
                        """;
            }
            if (!findingEmitted) {
                findingEmitted = true;
                return """
                        {"findings": [{"file": "A.java", "line": 1, "severity": "BLOCKER",
                         "category": "CORRECTNESS", "message": "可能空指针", "suggestion": "加判空",
                         "codeSnippet": "int x = 1;"}],
                         "summary": {"changedSymbols": ["A#go()"], "intent": "改了实现",
                                     "risks": [], "crossChunkQuestions": []}}
                        """;
            }
            return """
                    {"findings": [],
                     "summary": {"changedSymbols": ["A#go()"], "intent": "改了实现",
                                 "risks": [], "crossChunkQuestions": []}}
                    """;
        }
    }
}
