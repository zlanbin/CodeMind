package com.codemind.review.application;

import com.codemind.review.domain.changemap.ChangeMapAgent;
import com.codemind.review.domain.chunk.*;
import com.codemind.review.domain.dag.*;
import com.codemind.review.domain.scm.ScmContentPort;
import com.codemind.review.domain.task.ReviewTask;
import com.codemind.review.domain.task.ReviewTaskRepository;
import com.codemind.review.domain.task.ReviewTaskStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ExecutorService;

/**
 * 评审执行编排（M7a）：把「取 PR 内容 → 建 DAG 上下文 → 跑跨会话 Map-Reduce」串起来。
 *
 * <p>M3~M6 把引擎建好了但从未接线（`DagOrchestrator` 只在测试里被 new），本服务是那条缺失的线。
 *
 * <p>只处理 {@code CREATED}（首次）与 {@code FAILED}（续跑）两种状态的任务：
 * MQ 是 at-least-once，重复投递不该让同一个任务跑两遍 LLM。
 */
@Service
public class ReviewExecutionService {

    private static final Logger log = LoggerFactory.getLogger(ReviewExecutionService.class);

    private final ScmContentPort scmContentPort;
    private final ReviewTaskRepository taskRepository;
    private final DagOrchestrator orchestrator;
    private final ChangeMapAgent changeMapAgent;
    private final SemanticChunker chunker;
    private final ChunkRepository chunkRepository;
    private final ChunkBudget chunkBudget;
    private final ExecutorService reviewExecutor;

    private final ReviewCompletionService completion;
    private final com.codemind.review.domain.report.ReviewReportBuilder reportBuilder;
    private final com.codemind.review.domain.report.ReportPolicy reportPolicy;
    private final ReviewMarkdownRenderer renderer;
    private final com.codemind.review.domain.finding.FindingFilter findingFilter;
    public ReviewExecutionService(ScmContentPort scmContentPort,
                                  ReviewTaskRepository taskRepository,
                                  DagOrchestrator orchestrator,
                                  ChangeMapAgent changeMapAgent,
                                  SemanticChunker chunker,
                                  ChunkRepository chunkRepository,
                                  ChunkBudget chunkBudget,
                                  ExecutorService reviewExecutor,
                                  ReviewCompletionService completion,
                                  com.codemind.review.domain.report.ReviewReportBuilder reportBuilder,
                                  com.codemind.review.domain.report.ReportPolicy reportPolicy,
                                  ReviewMarkdownRenderer renderer,
                                  com.codemind.review.domain.finding.FindingFilter findingFilter) {
        this.scmContentPort = Objects.requireNonNull(scmContentPort, "scmContentPort");
        this.taskRepository = Objects.requireNonNull(taskRepository, "taskRepository");
        this.orchestrator = Objects.requireNonNull(orchestrator, "orchestrator");
        this.changeMapAgent = Objects.requireNonNull(changeMapAgent, "changeMapAgent");
        this.chunker = Objects.requireNonNull(chunker, "chunker");
        this.chunkRepository = Objects.requireNonNull(chunkRepository, "chunkRepository");
        this.chunkBudget = Objects.requireNonNull(chunkBudget, "chunkBudget");
        this.reviewExecutor = Objects.requireNonNull(reviewExecutor, "reviewExecutor");
        this.completion = Objects.requireNonNull(completion, "completion");
        this.reportBuilder = Objects.requireNonNull(reportBuilder, "reportBuilder");
        this.reportPolicy = Objects.requireNonNull(reportPolicy, "reportPolicy");
        this.renderer = Objects.requireNonNull(renderer, "renderer");
        this.findingFilter = Objects.requireNonNull(findingFilter, "findingFilter");
    }

    /**
     * 异步提交评审（M7a）。MQ 消费线程不能被几十秒的 LLM 调用阻塞——阻塞会触发消息重投。
     *
     * <p>评审失败只记日志，不外抛：单个 PR 评审失败不该让 MQ 反复重投同一条消息
     * （任务已标 FAILED，恢复驱动会捞它）。
     */
    public void submitAsync(String taskId) {
        if (reviewExecutor == null) {
            throw new IllegalStateException("未配置 reviewExecutor，无法异步执行");
        }
        reviewExecutor.submit(() -> {
            try {
                executeReview(taskId);
            } catch (RuntimeException e) {
                log.error("异步评审失败 taskId={}", taskId, e);
            }
        });
    }

    /**
     * 执行一个任务的完整评审，同步阻塞到 {@code AGGREGATING}。
     *
     * @return 评审结果；任务不存在或已在跑时返回 {@code null}
     * @throws IllegalStateException 取内容或评审失败（任务已标 FAILED）
     */
    public CrossSessionReviewResult executeReview(String taskId) {
        Optional<ReviewTask> found = taskRepository.findById(taskId);
        if (found.isEmpty()) {
            log.warn("任务不存在，跳过评审 taskId={}", taskId);
            return null;
        }
        ReviewTask task = found.get();
        if (task.status() != ReviewTaskStatus.CREATED && task.status() != ReviewTaskStatus.FAILED) {
            log.info("任务状态为 {}，已在评审中或已完成，跳过 taskId={}", task.status(), taskId);
            return null;
        }

        try {
            String diffText = scmContentPort.fetchDiff(task.repo(), task.prNumber());
            List<DiffFile> diffFiles = new DiffParser().parse(diffText);
            List<String> sourcePaths = sourcePathsOf(diffFiles);
            Map<String, String> newSources =
                    scmContentPort.fetchNewSources(task.repo(), task.headSha(), sourcePaths);
            log.info("任务 {} 取到 {} 个变更文件、{} 份新侧源码（请求 {} 份）",
                    taskId, diffFiles.size(), newSources.size(), sourcePaths.size());

            NodeContext context = NodeContext.create(taskId)
                    .withSharedState("diffText", diffText)
                    .withSharedState("newSources", newSources);

            // 节点按任务新建：它们持有本次任务的预算等状态，不做成单例 bean
            Node changeMapNode = new BuildChangeMapNode("build-change-map", changeMapAgent);
            Node planNode = new PlanChunksNode("plan-chunks", chunker,
                    chunkRepository, taskRepository, chunkBudget);

            CrossSessionReviewResult result;
            if (diffText.isBlank()) {
                task.transitionTo(ReviewTaskStatus.PLANNING);
                taskRepository.updateStatus(taskId, ReviewTaskStatus.PLANNING);
                task.transitionTo(ReviewTaskStatus.REVIEWING);
                taskRepository.updateStatus(taskId, ReviewTaskStatus.REVIEWING);
                task.transitionTo(ReviewTaskStatus.AGGREGATING);
                taskRepository.updateStatus(taskId, ReviewTaskStatus.AGGREGATING);
                result = new CrossSessionReviewResult(null, Map.of(),
                        com.codemind.review.domain.agent.GlobalReduceResult.degraded("变更为空，无可评审内容", 0));
            } else {
                result = orchestrator.executeCrossSessionReview(changeMapNode, planNode, task, context);
            }
            var filterOutcome = findingFilter.filter(task.repo(), result.allFindings());
            var report = reportBuilder.build(filterOutcome.kept(), diffFiles,
                    java.util.stream.Stream.concat(result.allDegradations().stream(),
                            filterOutcome.degradations().stream()).toList(),
                    reportPolicy);
            task.assignReport(report);
            var comments = report.inline().stream().map(comment ->
                    new com.codemind.common.event.ReviewCompleted.LineComment(comment.path(), comment.line(),
                            renderer.renderInline(comment))).toList();
            completion.register(com.codemind.common.event.ReviewCompleted.of(task.taskId(), task.repo(),
                    task.prNumber(), task.headSha(), renderer.renderSummary(report), comments));
            return result;
        } catch (Exception e) {
            markFailed(task);
            throw new IllegalStateException("评审执行失败 taskId=" + taskId, e);
        }
    }

    /**
     * 需要拉新侧源码的路径：只有 Java 文件要（AST 按方法边界切片用），且删除的文件没有新侧。
     * 重命名取 {@code newPath}——旧路径在 headSha 上已不存在，拉下去就是 404。
     */
    static List<String> sourcePathsOf(List<DiffFile> files) {
        return files.stream()
                .filter(DiffFile::isJava)
                .filter(f -> f.changeType() != ChangeType.DELETED)
                .map(DiffFile::path)
                .toList();
    }

    /** 标记失败并落库。状态机不允许从当前状态转 FAILED 时只记日志，不掩盖原异常。 */
    private void markFailed(ReviewTask task) {
        try {
            if (taskRepository.markFailedIfUnpublished(task.taskId())
                    && task.status().canTransitionTo(ReviewTaskStatus.FAILED)) {
                task.transitionTo(ReviewTaskStatus.FAILED);
            }
        } catch (RuntimeException e) {
            log.warn("标记任务失败时出错 taskId={} 当前状态={}", task.taskId(), task.status(), e);
        }
    }
}
