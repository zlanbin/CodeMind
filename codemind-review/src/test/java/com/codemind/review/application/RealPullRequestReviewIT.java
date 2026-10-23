package com.codemind.review.application;

import com.codemind.review.domain.chunk.DiffFile;
import com.codemind.review.domain.chunk.DiffParser;
import com.codemind.review.domain.dag.CrossSessionReviewResult;
import com.codemind.review.domain.finding.Finding;
import com.codemind.review.domain.scm.ScmContentPort;
import com.codemind.review.domain.task.ReviewTask;
import com.codemind.review.domain.task.ReviewTaskRepository;
import com.codemind.review.domain.task.ReviewTaskStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.TestPropertySource;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * M7a 验证点：真实 GitHub PR 自动走完入口链路到 {@code AGGREGATING} 并产出 Finding。
 *
 * <p><b>只读 GitHub</b>：拉 diff 与文件源码，不往 PR 写任何东西（回写属 M7b）。
 *
 * <p>前置（三者齐备才跑，否则 {@code assumeTrue} 跳过）：
 * <pre>
 * cd deploy &amp;&amp; docker compose up -d
 * # integration 必须在跑（本 IT 关了 discovery，Feign 直连 127.0.0.1:8081）
 * GITHUB_TOKEN=&lt;token&gt; GITHUB_WEBHOOK_SECRET=&lt;any&gt; \
 *   java -jar codemind-integration/target/codemind-integration-0.1.0-SNAPSHOT.jar &amp;
 * export GITHUB_TOKEN=&lt;细粒度 token，只授测试仓库&gt;
 * export CODEMIND_LLM_API_KEY=&lt;中转端点 key&gt;
 * export CODEMIND_TEST_REPO=zlanbin/codemind-sandbox
 * export CODEMIND_TEST_PR=1
 * export CODEMIND_TEST_HEAD_SHA=&lt;该 PR 的 head commit SHA&gt;
 * </pre>
 */
@SpringBootTest
@TestPropertySource(properties = {
        "spring.cloud.nacos.discovery.enabled=false",
        // 关了 discovery，Feign 直连本机 integration（须先启动它，见类注释前置条件）
        "codemind.integration.url=${CODEMIND_INTEGRATION_URL:http://127.0.0.1:8081}"
})
class RealPullRequestReviewIT {

    @org.springframework.test.context.bean.override.convention.TestBean(name = "rocketMqDomainEventPublisher", methodName = "publicationTestPublisher")
    private com.codemind.review.domain.event.DomainEventPublisher publicationTestPublisher;

    static com.codemind.review.domain.event.DomainEventPublisher publicationTestPublisher() {
        return new com.codemind.review.support.RecordingDomainEventPublisher();
    }

    @Autowired
    private ReviewExecutionService reviewExecutionService;

    @Autowired
    private ReviewTaskRepository taskRepository;

    @Autowired
    private ScmContentPort scmContentPort;

    @Autowired
    private JdbcClient jdbcClient;

    @Autowired
    private com.codemind.review.application.ReviewOutboxDispatcher dispatcher;

    @Autowired
    private com.codemind.review.domain.publication.ReviewOutboxRepository outbox;

    @Test
    void realPullRequestFlowsThroughToAggregating() {
        String repo = System.getenv("CODEMIND_TEST_REPO");
        String prNumber = System.getenv("CODEMIND_TEST_PR");
        String headSha = System.getenv("CODEMIND_TEST_HEAD_SHA");
        assumeTrue(notBlank(repo), "未设 CODEMIND_TEST_REPO，跳过真实 PR 端到端 IT");
        assumeTrue(notBlank(prNumber), "未设 CODEMIND_TEST_PR，跳过真实 PR 端到端 IT");
        assumeTrue(notBlank(headSha), "未设 CODEMIND_TEST_HEAD_SHA，跳过真实 PR 端到端 IT");
        assumeTrue(hasRealToken(), "未设真实 GITHUB_TOKEN，跳过真实 PR 端到端 IT");
        assumeTrue(hasRealLlmKey(), "未设真实 CODEMIND_LLM_API_KEY，跳过真实 PR 端到端 IT");

        int pr = Integer.parseInt(prNumber);

        // ① 真实拉到的 diff 非空且是合法 unified diff
        String diffText = scmContentPort.fetchDiff(repo, pr);
        assertNotNull(diffText);
        assertTrue(diffText.contains("diff --git"),
                "真实 diff 应含 diff --git，实际开头: "
                        + diffText.substring(0, Math.min(120, diffText.length())));
        List<DiffFile> diffFiles = new DiffParser().parse(diffText);
        Set<String> filesInDiff = diffFiles.stream().map(DiffFile::path).collect(java.util.stream.Collectors.toSet());
        System.out.println("真实 PR 变更文件: " + filesInDiff);

        // ① 续：Java 文件的新侧源码真拉到了。全部拉失败时评审会降级为 hunk 切片照样跑完，
        // 不单独断言就证不了「拉到文件源码」这条验证点
        List<String> javaPaths = ReviewExecutionService.sourcePathsOf(diffFiles);
        assertFalse(javaPaths.isEmpty(), "测试 PR 应含 Java 文件，否则本 IT 证不了源码拉取: " + filesInDiff);
        Map<String, String> sources = scmContentPort.fetchNewSources(repo, headSha, javaPaths);
        for (String path : javaPaths) {
            assertTrue(sources.containsKey(path) && !sources.get(path).isBlank(),
                    "Java 文件新侧源码应非空: " + path + "，实际拿到: " + sources.keySet());
        }

        // 同 (repo, pr, headSha) 唯一：清掉上一轮残留再插。插不进去必须失败而非跳过——
        // 跳过会让本 IT 承载的三个验证点被静默绕过（S6 评审）
        deleteExistingTasks(repo, pr, headSha);
        ReviewTask task = ReviewTask.create(repo, pr, headSha, "base-unused", java.util.UUID.randomUUID().toString());
        assertTrue(taskRepository.insertIfAbsent(task), "清理后仍插不进 review_task，检查唯一索引或清理逻辑");

        CrossSessionReviewResult result = reviewExecutionService.executeReview(task.taskId());

        // ② 切片非空
        assertNotNull(result);
        assertFalse(result.chunkResults().isEmpty(), "应至少切出一片");
        System.out.println("切片数: " + result.chunkResults().size());

        // ③ 状态推进到 AGGREGATING（PUBLISHED 属 M7b）。
        // 本地 task 是插库前的那个实例，executeReview 按 taskId findById 重建了另一个聚合，
        // 本地这个永远停在 CREATED——状态以库为准
        assertEquals(ReviewTaskStatus.AGGREGATING,
                taskRepository.findById(task.taskId()).orElseThrow().status());

        // ④ 真实 LLM 至少给一条意见（编排器挂到聚合上的就是 allFindings()，Finding 尚未落库）
        List<Finding> findings = result.allFindings();
        assertFalse(findings.isEmpty(), "真实 LLM 应至少产出一条 Finding");

        // ⑤ Finding 的 file 必须落在真实 diff 的文件列表内（M7b 回写靠这个字段定位）
        for (Finding f : findings) {
            System.out.printf("  %s:%d [%s/%s] %s%n",
                    f.file(), f.line(), f.severity(), f.category(), f.message());
            assertTrue(filesInDiff.contains(f.file()),
                    "Finding 的 file 不在 diff 文件列表里（LLM 编造了文件名）: " + f.file()
                            + "，diff 文件: " + filesInDiff);
        }

        var request = outbox.findByTaskId(task.taskId()).orElseThrow().event();
        assertEquals(headSha, request.headSha());
        assertTrue(dispatcher.dispatchTask(task.taskId()));
        assertTrue(((com.codemind.review.support.RecordingDomainEventPublisher) publicationTestPublisher)
                .events().contains(request), "只读 IT 的出口只能是记录型替身");
        System.out.println("降级信息: " + result.allDegradations());
    }

    /** 按子表 → 主表的顺序删（review_chunk 外键指向 review_task；node_run 的 task_id 是 varchar 无外键）。 */
    private void deleteExistingTasks(String repo, int pr, String headSha) {
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

    private static boolean notBlank(String v) {
        return v != null && !v.isBlank();
    }

    private static boolean hasRealToken() {
        return notBlank(System.getenv("GITHUB_TOKEN"));
    }

    private static boolean hasRealLlmKey() {
        String key = System.getenv("CODEMIND_LLM_API_KEY");
        return notBlank(key) && !key.equals("placeholder-no-key");
    }
}
