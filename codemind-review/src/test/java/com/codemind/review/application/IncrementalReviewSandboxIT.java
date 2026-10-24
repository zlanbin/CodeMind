package com.codemind.review.application;

import com.codemind.common.event.ReviewCompleted;
import com.codemind.common.event.ReviewPublished;
import com.codemind.review.domain.chunk.ChangeType;
import com.codemind.review.domain.chunk.DiffFile;
import com.codemind.review.domain.chunk.DiffParser;
import com.codemind.review.domain.dag.CrossSessionReviewResult;
import com.codemind.review.domain.event.DomainEventPublisher;
import com.codemind.review.domain.finding.TaskFingerprintPort;
import com.codemind.review.domain.publication.ReviewOutboxRepository;
import com.codemind.review.domain.scm.ScmContentPort;
import com.codemind.review.domain.task.LastReviewedHeadRepository;
import com.codemind.review.domain.task.ReviewTask;
import com.codemind.review.domain.task.ReviewTaskRepository;
import com.codemind.review.domain.task.ReviewTaskStatus;
import com.codemind.review.support.RealSandboxGithubClient;
import com.codemind.review.support.SandboxGithubClient;
import com.codemind.review.support.SandboxReviewCleanup;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * M10 验收：{@code zlanbin/codemind-sandbox} PR #1 第二次 push 只评审新 diff、不重复发评论。
 *
 * <p><b>只在 {@code -Pgithub-write} profile 下跑</b>（见 codemind-review/pom.xml），风格与
 * {@link RealPullRequestPublishIT} 一致——各阶段单独一次 {@code mvn verify}，之间停下等人工确认：
 *
 * <pre>
 * # baseline：对 PR 当前 head 走一次完整评审 + 真实发布，建立 last_reviewed_head 基准
 * mvn verify -pl codemind-review -am -Pgithub-write -Dit.test=IncrementalReviewSandboxIT \
 *     -Dfailsafe.failIfNoSpecifiedTests=false -Dcodemind.github-write.phase=baseline-prepare \
 *     -Dcodemind.github-write.expect-line=InventoryClient.java:11
 * ... -Dcodemind.github-write.phase=baseline-publish -Dcodemind.github-write.task-id=&lt;baseline-prepare 打印的 taskId&gt; \
 *     -Dcodemind.github-write.expect-line=InventoryClient.java:11
 *
 * # 人工操作：往 feat/discount 推一个新的真实 commit，更新 CODEMIND_TEST_HEAD_SHA 和下面的 expect-line
 *
 * # increment：应走 compare API 增量路径，只对新 diff 产出 Finding，发布后 PR 评论数只增不重复
 * ... -Dcodemind.github-write.phase=increment-prepare \
 *     -Dcodemind.github-write.expect-line=PriceCalculator.java:30
 * ... -Dcodemind.github-write.phase=increment-publish -Dcodemind.github-write.task-id=&lt;increment-prepare 打印的 taskId&gt; \
 *     -Dcodemind.github-write.expect-line=PriceCalculator.java:30
 * </pre>
 *
 * <p>前置：中间件 healthy；publish 阶段要求 integration 用新打的 jar 副本跑在 8081 并读生产 topic；
 * 没有其它 review 实例在跑；PR 作者与 token 用户不同；密钥用 source 仓库外的环境文件注入，不回显。
 */
@SpringBootTest
@TestPropertySource(properties = {
        "spring.cloud.nacos.discovery.enabled=false",
        "codemind.integration.url=${CODEMIND_INTEGRATION_URL:http://127.0.0.1:8081}",
        // 关了 discovery，指纹端点同 MemoryFeignClientIT 一样须直连本机 memory（先启动它）
        "codemind.memory.url=${CODEMIND_MEMORY_URL:http://127.0.0.1:8083}",
        "codemind.publication.dispatch-enabled=false",
        "codemind.fingerprint.dispatch-enabled=false",
        "codemind.mq.topic.review-completed=codemind-review-completed",
        "codemind.mq.topic.review-published=codemind-review-published",
        "codemind.mq.consumer-group.review-published=codemind-review-review-published-incremental",
        "rocketmq.producer.group=codemind-review-producer"
})
class IncrementalReviewSandboxIT {

    private static final String SANDBOX_REPO = "zlanbin/codemind-sandbox";
    private static final String PHASE = "codemind.github-write.phase";
    private static final String EXPECT_LINE = "codemind.github-write.expect-line";

    @TestConfiguration
    static class ReceiptCollectorConfig {
        @Bean
        ReceiptCollector receiptCollector(ObjectMapper objectMapper) {
            return new ReceiptCollector(objectMapper);
        }
    }

    @RocketMQMessageListener(topic = "${codemind.mq.topic.review-published}",
            consumerGroup = "codemind-review-receipt-collector-incremental")
    static class ReceiptCollector implements RocketMQListener<String> {
        final List<ReviewPublished> received = new CopyOnWriteArrayList<>();
        private final ObjectMapper objectMapper;

        ReceiptCollector(ObjectMapper objectMapper) { this.objectMapper = objectMapper; }

        @Override
        public void onMessage(String payload) {
            try {
                received.add(objectMapper.readValue(payload, ReviewPublished.class));
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }

        boolean anySince(String taskId, Instant since) {
            return received.stream().anyMatch(r -> r.taskId().equals(taskId) && !r.occurredAt().isBefore(since));
        }
    }

    @Autowired private ReviewExecutionService reviewExecutionService;
    @Autowired private ReviewTaskRepository taskRepository;
    @Autowired private ScmContentPort scmContentPort;
    @Autowired private JdbcClient jdbcClient;
    @Autowired private ReviewOutboxRepository outbox;
    @Autowired private ReviewOutboxDispatcher dispatcher;
    @Autowired private FingerprintRegistrationDispatcher fingerprintDispatcher;
    @Autowired private LastReviewedHeadRepository lastReviewedHeadRepository;
    @Autowired private TaskFingerprintPort taskFingerprintPort;
    @Autowired private DomainEventPublisher eventPublisher;
    @Autowired private ReceiptCollector receiptCollector;

    @Test
    void secondPushReviewsOnlyIncrementalDiffWithoutDuplicateComments() {
        String token = requireEnv("GITHUB_TOKEN");
        String llmKey = requireEnv("CODEMIND_LLM_API_KEY");
        assertNotEquals("placeholder-no-key", llmKey, "CODEMIND_LLM_API_KEY 仍是占位值");
        int pr = Integer.parseInt(requireEnv("CODEMIND_TEST_PR"));
        String headSha = requireEnv("CODEMIND_TEST_HEAD_SHA");
        assertTrue(headSha.matches("[0-9a-f]{40}"), "CODEMIND_TEST_HEAD_SHA 必须是 40 位完整 SHA");
        String phase = System.getProperty(PHASE, "");
        assertFalse(phase.isBlank(), "必须显式给 -D" + PHASE + "，不猜默认阶段");

        var github = new RealSandboxGithubClient(RestClient.builder(), "https://api.github.com", token);
        assertEquals(headSha, github.headSha(SANDBOX_REPO, pr), "PR 当前 head 与配置的不一致，停下不猜");

        switch (phase) {
            case "baseline-prepare" -> prepare(github, pr, headSha, null);
            case "baseline-publish" -> publish(github, pr, headSha, null);
            case "increment-prepare" -> prepare(github, pr, headSha, headSha);
            case "increment-publish" -> publish(github, pr, headSha, headSha);
            default -> fail("未知 phase: " + phase + "，只接受 baseline-prepare / baseline-publish / increment-prepare / increment-publish");
        }
    }

    /**
     * 只读 GitHub + 真实 LLM：跑一次评审（增量阶段应走 compare API），存 outbox，打印待发内容。
     *
     * @param incrementExpected 非空表示本次应是增量评审（last_reviewed_head 已有基准）
     */
    private void prepare(SandboxGithubClient github, int pr, String headSha, String incrementExpected) {
        String[] target = requireExpectedLine();
        printCleanupCandidates(github, pr);

        List<DiffFile> diffFiles = fetchDiffFiles(pr);
        Set<String> filesInDiff = diffFiles.stream().map(DiffFile::path).collect(Collectors.toSet());

        var priorBaseline = lastReviewedHeadRepository.findFor(SANDBOX_REPO, pr);
        if (incrementExpected != null) {
            assertTrue(priorBaseline.isPresent(), "增量阶段要求已有 last_reviewed_head 基准，先跑完 baseline 两步");
        }

        deleteExistingTasks(SANDBOX_REPO, pr, headSha);
        String baseSha = priorBaseline.map(com.codemind.review.domain.task.LastReviewedHead::baseSha)
                .orElse("base-unused");
        ReviewTask task = ReviewTask.create(SANDBOX_REPO, pr, headSha, baseSha, UUID.randomUUID().toString());
        assertTrue(taskRepository.insertIfAbsent(task), "清理后仍插不进 review_task");

        CrossSessionReviewResult result = reviewExecutionService.executeReview(task.taskId());
        ReviewCompleted event;
        try {
            assertNotNull(result);
            assertFalse(result.allFindings().isEmpty(),
                    "真实 LLM 应至少产出一条 Finding；验收失败不能手工造 finding 后声称通过");
            for (var finding : result.allFindings()) {
                assertTrue(filesInDiff.contains(finding.file()), "Finding 的 file 不在 diff 文件列表里: " + finding.file());
            }
            markAsTestPublication(task.taskId());
            event = outbox.findByTaskId(task.taskId())
                    .orElseThrow(() -> new AssertionError("prepare 后应已保存发布请求")).event();
            assertTrue(event.summaryBody().contains(SandboxReviewCleanup.TEST_MARKER), "测试标记应已写进待发正文");
            for (var comment : event.comments()) {
                assertOnNewSide(diffFiles, comment.path(), comment.line());
            }
            assertTrue(event.comments().stream().anyMatch(c -> hits(c.path(), c.line(), target)),
                    "行级评论没有命中预埋缺陷 " + String.join(":", target) + "，验收失败");

            if (incrementExpected != null) {
                assertFalse(event.fingerprints().isEmpty(), "增量评审应产出至少一个 Finding 指纹");
            }
        } catch (AssertionError | RuntimeException e) {
            discardPendingRequest(task.taskId());
            throw e;
        }

        System.out.println("=== PREPARE 完成（" + (incrementExpected != null ? "增量" : "全量基线") + "），等待人工确认后再执行 publish 阶段 ===");
        System.out.println("taskId = " + task.taskId());
        System.out.println("目标 PR = " + SANDBOX_REPO + " #" + pr + " @ " + headSha);
        System.out.println("本次评审前已有基准: " + priorBaseline.map(b -> b.headSha()).orElse("(无，首次)"));
        System.out.println("本次产出指纹数: " + event.fingerprints().size());
        System.out.println("总结正文：\n" + event.summaryBody());
        System.out.println("行级评论（" + event.comments().size() + " 条，上限 10）：");
        for (var comment : event.comments()) {
            System.out.println("  " + comment.path() + ":" + comment.line() + " -> " + comment.body());
        }
        System.out.println("=== outbox 仍未投递；确认内容后带上面的 taskId 执行 publish 阶段 ===");
    }

    /** 只对已确认的单个 taskId 投递；等真实回执，核对 last_reviewed_head/指纹账本已落地，回查 GitHub。 */
    private void publish(SandboxGithubClient github, int pr, String headSha, String incrementExpected) {
        String[] target = requireExpectedLine();
        String taskId = requireSystemProperty("codemind.github-write.task-id");
        ReviewTask task = taskRepository.findById(taskId)
                .orElseThrow(() -> new AssertionError("未找到 taskId=" + taskId + "，先跑 prepare 阶段"));
        assertEquals(SANDBOX_REPO, task.repo(), "目标仓库变了，停下不猜");
        assertEquals(pr, task.prNumber(), "目标 PR 变了，停下不猜");
        assertEquals(headSha, task.headSha(), "目标 commit 变了，停下不猜");
        ReviewCompleted saved = outbox.findByTaskId(taskId)
                .orElseThrow(() -> new AssertionError("outbox 里没有这个 taskId 的待发请求")).event();
        assertTrue(saved.summaryBody().contains(SandboxReviewCleanup.TEST_MARKER),
                "待发正文缺测试标记，不是本验收 prepare 出来的请求");

        int commentsBefore = github.listComments(SANDBOX_REPO, pr).size();
        System.out.println("=== PUBLISH：即将向 " + SANDBOX_REPO + " #" + pr + " 发送真实 review（发布前评论总数="
                + commentsBefore + "）===");
        System.out.println("taskId = " + taskId + "，行级评论 " + saved.comments().size() + " 条");

        Instant publishStartedAt = Instant.now();
        boolean dispatched = dispatcher.dispatchTask(taskId);
        boolean alreadyPublished = outbox.findByTaskId(taskId).map(e -> e.acknowledgedAt() != null).orElse(false);
        assertTrue(dispatched || alreadyPublished, "既没有投递、这条请求也没有被确认过");

        await(Duration.ofSeconds(60), () -> taskRepository.findById(taskId)
                .map(ReviewTask::status).filter(s -> s == ReviewTaskStatus.PUBLISHED).isPresent());
        await(Duration.ofSeconds(30), () -> outbox.findByTaskId(taskId).map(e -> e.acknowledgedAt() != null).orElse(false));
        await(Duration.ofSeconds(30), () -> receiptCollector.anySince(taskId, publishStartedAt));

        // markPublished 应已落 last_reviewed_head 基准
        var baseline = lastReviewedHeadRepository.findFor(SANDBOX_REPO, pr);
        assertTrue(baseline.isPresent(), "发布成功后应已有 last_reviewed_head 记录");
        assertEquals(headSha, baseline.get().headSha(), "last_reviewed_head 的 headSha 应更新为本次 head");

        // 指纹登记账本应已有这条、手动驱动一次 dispatcher 让它真正登记到 memory
        int dispatchedFingerprints = fingerprintDispatcher.dispatchDue();
        System.out.println("本轮指纹登记 dispatchDue 处理了 " + dispatchedFingerprints + " 条");
        Set<String> published = taskFingerprintPort.published(SANDBOX_REPO, pr);
        for (String fp : saved.fingerprints()) {
            assertTrue(published.contains(fp), "指纹 " + fp + " 应已登记到 memory 的已发布指纹表");
        }

        var reviewResult = assertPublishedOnGithub(github, pr, taskId, target);
        int commentsAfter = github.listComments(SANDBOX_REPO, pr).size();
        System.out.println("发布后评论总数=" + commentsAfter + "（新增 " + (commentsAfter - commentsBefore) + " 条）");
        assertTrue(commentsAfter > commentsBefore, "发布后评论数应增加");
        if (incrementExpected != null) {
            assertTrue(commentsAfter - commentsBefore <= 10,
                    "增量评审新增评论数应在合理上限内（不该把旧 diff 重新评一遍）: " + (commentsAfter - commentsBefore));
        }

        System.out.println("=== PUBLISH 完成，请到网页确认: https://github.com/" + SANDBOX_REPO + "/pull/" + pr + " ===");
        System.out.println("本次 review 下行级评论内容:");
        for (var c : reviewResult.comments()) {
            System.out.println("  " + c.path() + ":" + c.line() + " -> " + c.body());
        }
    }

    private record PublishedReview(long reviewId, List<SandboxGithubClient.SandboxComment> comments) {}

    /** GitHub 上恰好一个本任务 review，行级评论都在新侧 diff 内、至少一条命中预埋缺陷。 */
    private PublishedReview assertPublishedOnGithub(SandboxGithubClient github, int pr, String taskId, String[] target) {
        var marked = github.listReviews(SANDBOX_REPO, pr).stream()
                .filter(r -> r.body() != null && r.body().contains("<!-- codemind:task=" + taskId + " -->"))
                .toList();
        assertEquals(1, marked.size(), "应恰好有一个带本任务标记的 review，实际: " + marked.size());
        long reviewId = marked.get(0).id();
        var comments = github.listComments(SANDBOX_REPO, pr).stream()
                .filter(c -> c.reviewId() != null && c.reviewId() == reviewId).toList();
        assertTrue(comments.size() >= 1 && comments.size() <= 10, "行级评论数应在 1~10 之间，实际: " + comments.size());
        List<DiffFile> diffFiles = fetchDiffFiles(pr);
        for (var comment : comments) {
            assertNotNull(comment.line(), "行级评论 " + comment.id() + " 没有新侧行号（outdated？）");
            assertOnNewSide(diffFiles, comment.path(), comment.line());
        }
        assertTrue(comments.stream().anyMatch(c -> hits(c.path(), c.line(), target)),
                "GitHub 上的行级评论没有命中预埋缺陷 " + String.join(":", target));
        return new PublishedReview(reviewId, comments);
    }

    private void printCleanupCandidates(SandboxGithubClient github, int pr) {
        var pending = new SandboxReviewCleanup(github, SANDBOX_REPO, pr, protectedTaskIds(pr)).listCommentsPendingDeletion();
        System.out.println("=== 可清理的旧测试评论（只读列出，未删除）===");
        if (pending.isEmpty()) {
            System.out.println("  无，不需要 cleanup");
            return;
        }
        for (var c : pending) {
            System.out.println("  id=" + c.id() + " author=" + c.authorLogin() + " " + c.path() + ":" + c.line());
        }
    }

    private List<DiffFile> fetchDiffFiles(int pr) {
        String diffText = scmContentPort.fetchDiff(SANDBOX_REPO, pr);
        assertNotNull(diffText);
        assertTrue(diffText.contains("diff --git"), "真实 diff 应含 diff --git");
        return new DiffParser().parse(diffText);
    }

    private static void assertOnNewSide(List<DiffFile> diffFiles, String path, int line) {
        DiffFile file = diffFiles.stream().filter(f -> f.path().equals(path)).findFirst()
                .orElseThrow(() -> new AssertionError("行级评论的文件不在 diff 里: " + path));
        assertNotEquals(ChangeType.DELETED, file.changeType(), "行级评论落在已删除文件上: " + path);
        assertTrue(file.hunks().stream().anyMatch(h -> h.coversNewLine(line)),
                "行级评论不在新侧 hunk 范围内: " + path + ":" + line);
    }

    /**
     * target[1] 为 {@code *} 时只核对文件、不核对具体行号——真实 LLM 调用对同一个缺陷落在
     * 哪一行、是否与邻近问题合并成一条意见不是确定性的，连续两次按固定行号验收都因为
     * LLM 把新缺陷并入别的意见而断言失败（不是代码 bug）；只要求命中文件，仍能证明
     * 本次评审确实覆盖了新 diff 里的目标文件，不退化成不验证任何东西。
     */
    private static boolean hits(String path, Integer line, String[] target) {
        boolean pathMatches = path.equals(target[0]) || path.endsWith("/" + target[0]);
        if ("*".equals(target[1])) {
            return pathMatches;
        }
        return line != null && line == Integer.parseInt(target[1]) && pathMatches;
    }

    private static String[] requireExpectedLine() {
        String[] target = requireSystemProperty(EXPECT_LINE).split(":");
        assertTrue(target.length == 2 && ("*".equals(target[1]) || target[1].matches("\\d+")),
                "-D" + EXPECT_LINE + " 格式应为 <文件路径或文件名>:<新侧行号或 *>");
        return target;
    }

    private void markAsTestPublication(String taskId) {
        int updated = jdbcClient.sql("""
                update review_publication_outbox
                set payload = jsonb_set(payload, '{summaryBody}',
                        to_jsonb((payload->>'summaryBody') || :marker)), updated_at = now()
                where task_id = cast(:id as uuid) and attempts = 0 and acknowledged_at is null
                """).param("marker", "\n\n" + SandboxReviewCleanup.TEST_MARKER).param("id", taskId).update();
        assertEquals(1, updated, "给待发请求加测试标记失败（请求不存在或已被领取过）");
    }

    private void discardPendingRequest(String taskId) {
        jdbcClient.sql("""
                delete from review_publication_outbox
                where task_id = cast(:id as uuid) and attempts = 0 and acknowledged_at is null
                """).param("id", taskId).update();
    }

    private Set<String> protectedTaskIds(int pr) {
        Boolean ledgerExists = jdbcClient.sql("select to_regclass('integration.review_publication') is not null")
                .query(Boolean.class).single();
        if (!Boolean.TRUE.equals(ledgerExists)) {
            return Set.of();
        }
        return Set.copyOf(jdbcClient.sql("""
                select task_id from integration.review_publication
                where repo = :repo and pr_number = :pr and state in ('IN_FLIGHT', 'UNCERTAIN')
                """).param("repo", SANDBOX_REPO).param("pr", pr).query(String.class).list());
    }

    private void deleteExistingTasks(String repo, int pr, String headSha) {
        Set<String> unsettled = protectedTaskIds(pr);
        List<String> existing = jdbcClient.sql("""
                select cast(task_id as varchar) from review_task where repo = :repo and pr_number = :pr and head_sha = :sha
                """).param("repo", repo).param("pr", pr).param("sha", headSha).query(String.class).list();
        List<String> blocked = existing.stream().filter(unsettled::contains).toList();
        assertTrue(blocked.isEmpty(), "这些任务的发送结果未定（IN_FLIGHT/UNCERTAIN），先核查: " + blocked);

        jdbcClient.sql("""
                delete from review_publication_outbox where task_id in (select task_id from review_task
                    where repo=:repo and pr_number=:pr and head_sha=:sha)
                """).param("repo", repo).param("pr", pr).param("sha", headSha).update();
        jdbcClient.sql("""
                delete from node_run where task_id in (select cast(task_id as varchar) from review_task
                    where repo = :repo and pr_number = :pr and head_sha = :sha)
                """).param("repo", repo).param("pr", pr).param("sha", headSha).update();
        jdbcClient.sql("""
                delete from review_chunk where task_id in (select task_id from review_task
                    where repo = :repo and pr_number = :pr and head_sha = :sha)
                """).param("repo", repo).param("pr", pr).param("sha", headSha).update();
        jdbcClient.sql("delete from review_task where repo = :repo and pr_number = :pr and head_sha = :sha")
                .param("repo", repo).param("pr", pr).param("sha", headSha).update();
    }

    private static void await(Duration timeout, BooleanSupplier condition) {
        if (!waitFor(timeout, condition)) {
            fail("等待超时: " + timeout);
        }
    }

    private static boolean waitFor(Duration timeout, BooleanSupplier condition) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        }
        return condition.getAsBoolean();
    }

    private static String requireEnv(String name) {
        String value = System.getenv(name);
        assertTrue(value != null && !value.isBlank(), "缺少环境变量 " + name + "（显式验收不跳过，直接失败）");
        return value;
    }

    private static String requireSystemProperty(String name) {
        String value = System.getProperty(name);
        assertTrue(value != null && !value.isBlank(), "缺少系统属性 -D" + name + "（显式验收不跳过，直接失败）");
        return value;
    }
}
