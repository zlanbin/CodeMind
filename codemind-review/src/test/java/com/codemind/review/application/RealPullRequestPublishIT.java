package com.codemind.review.application;

import com.codemind.common.event.ReviewCompleted;
import com.codemind.common.event.ReviewPublished;
import com.codemind.review.domain.chunk.ChangeType;
import com.codemind.review.domain.chunk.DiffFile;
import com.codemind.review.domain.chunk.DiffParser;
import com.codemind.review.domain.dag.CrossSessionReviewResult;
import com.codemind.review.domain.event.DomainEventPublisher;
import com.codemind.review.domain.publication.ReviewOutboxRepository;
import com.codemind.review.domain.scm.ScmContentPort;
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
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * M7b Task 10 真实写验收：往 {@code zlanbin/codemind-sandbox} 发一个 GitHub review。
 *
 * <p><b>只在 {@code -Pgithub-write} profile 下跑</b>（见 codemind-review/pom.xml）。三个阶段，
 * 各自一次 {@code mvn verify} 调用，阶段之间停下等人工确认：
 *
 * <pre>
 * # prepare：只读 GitHub + 真实 LLM，评审结果存进 outbox，不投递；只读列出可清理的旧测试评论
 * mvn verify -pl codemind-review -am -Pgithub-write -Dit.test=RealPullRequestPublishIT \
 *     -Dfailsafe.failIfNoSpecifiedTests=false -Dcodemind.github-write.phase=prepare \
 *     -Dcodemind.github-write.expect-line=InventoryClient.java:11
 *
 * # cleanup（可选）：只删用户确认过的那批旧测试评论 id，候选有变化就整体放弃
 * ... -Dcodemind.github-write.phase=cleanup -Dcodemind.github-write.confirm-delete=&lt;id1,id2&gt;
 *
 * # publish：只对已确认的 taskId 投递，真实发一个 review 到 sandbox PR，再重投一次验幂等
 * ... -Dcodemind.github-write.phase=publish -Dcodemind.github-write.task-id=&lt;prepare 打印的 taskId&gt; \
 *     -Dcodemind.github-write.expect-line=InventoryClient.java:11
 * </pre>
 *
 * <p>这是显式选择的验收，缺任何前置（token、LLM key、PR、完整 head SHA、阶段参数）都直接失败，
 * 不用 assumeTrue 跳过——跳过会让 {@code -Pgithub-write} 在什么都没验的情况下亮绿灯。
 *
 * <p>前置：中间件 healthy；publish 阶段要求 integration 用新打的 jar 副本跑在 8081 并读生产 topic
 * （真实发布是 review 发 MQ 事件、integration 消费后调真实 GitHub，不是本进程直接调）；
 * 没有其它 review 实例在跑；PR 作者与 token 用户不同；密钥用 {@code source} 仓库外的环境文件注入，不回显。
 */
@SpringBootTest
@TestPropertySource(properties = {
        "spring.cloud.nacos.discovery.enabled=false",
        "codemind.integration.url=${CODEMIND_INTEGRATION_URL:http://127.0.0.1:8081}",
        // 验收不开后台投递；只手动对确认过的单个 taskId 调 dispatchTask，不扫描其它 outbox 数据。
        "codemind.publication.dispatch-enabled=false",
        // 本机 src/test/resources/application.properties 把这些全改成了 "-test" 后缀的隔离值
        // （日常回归故意不碰真实 topic）。验收必须显式覆盖回生产名，否则这个进程发出的消息
        // 会落在 integration 从未订阅的 topic 上，静默丢失（第一次真实验收踩过这个坑：
        // dispatchTask 返回 true、没有任何异常，但 broker 上压根没有这个 topic 的路由信息，
        // RocketMQ 从不校验 topic 是否有人订阅就能发送成功，因为 topic 不存在时会自动创建）。
        "codemind.mq.topic.review-completed=codemind-review-completed",
        "codemind.mq.topic.review-published=codemind-review-published",
        "codemind.mq.consumer-group.review-published=codemind-review-review-published",
        "rocketmq.producer.group=codemind-review-producer"
})
class RealPullRequestPublishIT {

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

    /** 独立消费组收集真实回执，用来证明重投确实被 integration 处理过，而不是被 MQ 静默丢了。 */
    @RocketMQMessageListener(topic = "${codemind.mq.topic.review-published}",
            consumerGroup = "codemind-review-receipt-collector-github-write")
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

        long countSince(String taskId, Instant since) {
            return received.stream().filter(r -> r.taskId().equals(taskId))
                    .filter(r -> !r.occurredAt().isBefore(since)).count();
        }
    }

    @Autowired private ReviewExecutionService reviewExecutionService;
    @Autowired private ReviewTaskRepository taskRepository;
    @Autowired private ScmContentPort scmContentPort;
    @Autowired private JdbcClient jdbcClient;
    @Autowired private ReviewOutboxRepository outbox;
    @Autowired private ReviewOutboxDispatcher dispatcher;
    @Autowired private DomainEventPublisher eventPublisher;
    @Autowired private ReceiptCollector receiptCollector;

    @Test
    void preparesCleansOrPublishesPerExplicitPhase() {
        String token = requireEnv("GITHUB_TOKEN");
        String llmKey = requireEnv("CODEMIND_LLM_API_KEY");
        assertNotEquals("placeholder-no-key", llmKey, "CODEMIND_LLM_API_KEY 仍是占位值");
        int pr = Integer.parseInt(requireEnv("CODEMIND_TEST_PR"));
        String headSha = requireEnv("CODEMIND_TEST_HEAD_SHA");
        assertTrue(headSha.matches("[0-9a-f]{40}"),
                "CODEMIND_TEST_HEAD_SHA 必须是 40 位完整 SHA（GitHub 的 commit_id 不接受短 SHA）");
        String phase = System.getProperty(PHASE, "prepare"); // prepare 只写本地库，默认它；写 GitHub 的阶段必须显式给

        var github = new RealSandboxGithubClient(RestClient.builder(), "https://api.github.com", token);
        assertEquals(headSha, github.headSha(SANDBOX_REPO, pr), "PR 当前 head 与配置的不一致，停下不猜");

        switch (phase) {
            case "prepare" -> prepare(github, pr, headSha);
            case "cleanup" -> cleanup(github, pr);
            case "publish" -> publish(github, pr, headSha);
            default -> fail("未知 phase: " + phase + "，只接受 prepare / cleanup / publish");
        }
    }

    /** 只读 GitHub + 真实 LLM：列出可清理的旧测试评论、跑评审、存 outbox、打印待发内容，不触碰发布链路。 */
    private void prepare(SandboxGithubClient github, int pr, String headSha) {
        String[] target = requireExpectedLine();
        printCleanupCandidates(github, pr);

        List<DiffFile> diffFiles = fetchDiffFiles(pr);
        Set<String> filesInDiff = diffFiles.stream().map(DiffFile::path).collect(Collectors.toSet());

        deleteExistingTasks(SANDBOX_REPO, pr, headSha);
        ReviewTask task = ReviewTask.create(SANDBOX_REPO, pr, headSha, "base-unused", UUID.randomUUID().toString());
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
        } catch (AssertionError | RuntimeException e) {
            discardPendingRequest(task.taskId()); // 验收没过的请求不能留在 outbox 里等别的实例发出去
            throw e;
        }

        System.out.println("=== PREPARE 完成，等待人工确认后再执行 publish 阶段 ===");
        System.out.println("taskId = " + task.taskId());
        System.out.println("目标 PR = " + SANDBOX_REPO + " #" + pr + " @ " + headSha);
        System.out.println("总结正文：\n" + event.summaryBody());
        System.out.println("行级评论（" + event.comments().size() + " 条，上限 10）：");
        for (var comment : event.comments()) {
            System.out.println("  " + comment.path() + ":" + comment.line() + " -> " + comment.body());
        }
        System.out.println("=== outbox 仍未投递；确认内容后带上面的 taskId 执行 publish 阶段 ===");
    }

    /** 只删用户确认过的旧测试评论；候选与确认不一致整体放弃。 */
    private void cleanup(SandboxGithubClient github, int pr) {
        Set<Long> confirmed = Arrays.stream(requireSystemProperty("codemind.github-write.confirm-delete").split(","))
                .map(String::trim).filter(s -> !s.isEmpty()).map(Long::parseLong).collect(Collectors.toSet());
        assertFalse(confirmed.isEmpty(), "confirm-delete 为空：没有要删的就不需要跑 cleanup");
        List<Long> deleted = new SandboxReviewCleanup(github, SANDBOX_REPO, pr, protectedTaskIds(pr))
                .deleteConfirmed(confirmed);
        System.out.println("=== CLEANUP 完成，已删除评论 id: " + deleted + "（review 总结不可编辑，保留原样）===");
    }

    /** 只对已确认的单个 taskId 投递；等真实回执，回查 GitHub 落地结果，再重投一次验证不重复发。 */
    private void publish(SandboxGithubClient github, int pr, String headSha) {
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

        System.out.println("=== PUBLISH：即将向 " + SANDBOX_REPO + " #" + pr + " 发送真实 review ===");
        System.out.println("taskId = " + taskId + "，行级评论 " + saved.comments().size() + " 条");

        // 已确认（acknowledged_at 非空）的请求领不到，这是幂等保护本身在生效，不是失败。
        boolean dispatched = dispatcher.dispatchTask(taskId);
        boolean alreadyPublished = outbox.findByTaskId(taskId).map(e -> e.acknowledgedAt() != null).orElse(false);
        assertTrue(dispatched || alreadyPublished, "既没有投递、这条请求也没有被确认过");

        await(Duration.ofSeconds(60), () -> taskRepository.findById(taskId)
                .map(ReviewTask::status).filter(s -> s == ReviewTaskStatus.PUBLISHED).isPresent());
        await(Duration.ofSeconds(30), () -> outbox.findByTaskId(taskId).map(e -> e.acknowledgedAt() != null).orElse(false));

        var reviewId = assertPublishedOnGithub(github, pr, taskId, target);

        // 再发同一事件（同 eventId、同正文），等 integration 处理完给出新回执，GitHub 上仍只有这一个 review。
        Instant redeliveredAt = Instant.now();
        boolean receipted = false;
        for (int attempt = 1; attempt <= 3 && !receipted; attempt++) {
            eventPublisher.publish(saved); // 新消费组首次再平衡前的消息会被跳过，故最多重发 3 次
            receipted = waitFor(Duration.ofSeconds(45), () -> receiptCollector.countSince(taskId, redeliveredAt) > 0);
        }
        assertTrue(receipted, "重投后 135s 内没有收到新回执，无法证明重投被处理过");
        assertEquals(reviewId, assertPublishedOnGithub(github, pr, taskId, target), "重投后 review 变了");

        System.out.println("=== PUBLISH 完成，请到网页确认: https://github.com/" + SANDBOX_REPO + "/pull/" + pr + " ===");
    }

    /** GitHub 上恰好一个本任务 review，行级 1~10 条、都在新侧 diff 内、至少一条命中预埋缺陷。返回 review id。 */
    private long assertPublishedOnGithub(SandboxGithubClient github, int pr, String taskId, String[] target) {
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
        return reviewId;
    }

    private void printCleanupCandidates(SandboxGithubClient github, int pr) {
        var pending = new SandboxReviewCleanup(github, SANDBOX_REPO, pr, protectedTaskIds(pr)).listCommentsPendingDeletion();
        System.out.println("=== 可清理的旧测试评论（只读列出，未删除）===");
        if (pending.isEmpty()) {
            System.out.println("  无，不需要 cleanup");
            return;
        }
        for (var c : pending) {
            System.out.println("  id=" + c.id() + " author=" + c.authorLogin() + " " + c.path() + ":" + c.line()
                    + " https://github.com/" + SANDBOX_REPO + "/pull/" + pr + "#discussion_r" + c.id());
        }
        System.out.println("  确认后执行 cleanup 阶段：-D" + PHASE + "=cleanup -Dcodemind.github-write.confirm-delete="
                + pending.stream().map(c -> String.valueOf(c.id())).collect(Collectors.joining(",")));
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

    private static boolean hits(String path, Integer line, String[] target) {
        return line != null && line == Integer.parseInt(target[1])
                && (path.equals(target[0]) || path.endsWith("/" + target[0]));
    }

    private static String[] requireExpectedLine() {
        String[] target = requireSystemProperty(EXPECT_LINE).split(":");
        assertTrue(target.length == 2 && target[1].matches("\\d+"),
                "-D" + EXPECT_LINE + " 格式应为 <文件路径或文件名>:<新侧行号>，即目标 PR 预埋缺陷的位置");
        return target;
    }

    /** 在保存的待发正文上追加测试标记——发出前写入 outbox，投递时不再改 payload。 */
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

    /** integration 发送账本里发送结果未定的任务；账本表不存在（integration 从没起过）时为空。 */
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

    /** 按子表 → 主表的顺序删；发送结果未定的任务是核查对象，碰到就停下。 */
    private void deleteExistingTasks(String repo, int pr, String headSha) {
        Set<String> unsettled = protectedTaskIds(pr);
        List<String> existing = jdbcClient.sql("""
                select cast(task_id as varchar) from review_task where repo = :repo and pr_number = :pr and head_sha = :sha
                """).param("repo", repo).param("pr", pr).param("sha", headSha).query(String.class).list();
        List<String> blocked = existing.stream().filter(unsettled::contains).toList();
        assertTrue(blocked.isEmpty(), "这些任务的发送结果未定（IN_FLIGHT/UNCERTAIN），先按 docs/operations/M7b-publication.md 核查: "
                + blocked);

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
