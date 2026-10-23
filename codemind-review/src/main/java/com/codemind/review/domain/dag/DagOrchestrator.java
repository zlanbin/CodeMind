package com.codemind.review.domain.dag;

import com.codemind.review.domain.agent.GlobalReduceAgent;
import com.codemind.review.domain.agent.GlobalReduceResult;
import com.codemind.review.domain.agent.ReviewAgent;
import com.codemind.review.domain.agent.ReviewDimension;
import com.codemind.review.domain.changemap.ChangeMap;
import com.codemind.review.domain.chunk.ReviewChunk;
import com.codemind.review.domain.task.ReviewTask;
import com.codemind.review.domain.task.ReviewTaskRepository;
import com.codemind.review.domain.task.ReviewTaskStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * DAG 编排器（M5 引入）：动态扇出 + 调度包装。
 *
 * <p>解决 M3 遗留的「PlanChunks 产出 chunks → 为每个 chunk × 维度 fan-out 评审节点」动态扇出问题。
 *
 * <p>执行流程：
 * <ol>
 *   <li>调用 {@link PlanChunksNode} 产出 {@code List<ReviewChunk>}；</li>
 *   <li>为每个 chunk × 每个维度动态创建 {@link ReviewChunkNode}（4 维度并行）；</li>
 *   <li>为每个 chunk 创建 {@link ChunkReduceNode}（归约多维度结果，按 fingerprint 去重）；</li>
 *   <li>构建完整 DAG 并交给 {@link DagScheduler} 执行。</li>
 * </ol>
 *
 * <p>不修改 {@code DagDefinition} 和 {@code DagScheduler}（它们按既定拓扑调度），
 * 而是在编排层根据运行时切片结果动态生成子图。
 */
public final class DagOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(DagOrchestrator.class);

    private final DagScheduler scheduler;
    private final Map<ReviewDimension, ReviewAgent> agents;
    private final GlobalReduceAgent globalReduceAgent;
    private final ReviewTaskRepository taskRepository;

    /**
     * M5 签名：只能跑到 ChunkReduce，没有全局归约。
     */
    public DagOrchestrator(DagScheduler scheduler, Map<ReviewDimension, ReviewAgent> agents) {
        this(scheduler, agents, null, null);
    }

    /**
     * @param scheduler         拓扑调度器
     * @param agents            各维度 Agent（Style / Security / Perf / Test）
     * @param globalReduceAgent 全局归约 Agent（M6；为 null 时不能跑跨会话流程）
     * @param taskRepository    任务仓储，用于状态流转落库（可为 null，此时只在内存推进状态）
     */
    public DagOrchestrator(DagScheduler scheduler, Map<ReviewDimension, ReviewAgent> agents,
                           GlobalReduceAgent globalReduceAgent, ReviewTaskRepository taskRepository) {
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.globalReduceAgent = globalReduceAgent;
        this.taskRepository = taskRepository;
        this.agents = Map.copyOf(agents);
        if (agents.size() != 4) {
            throw new IllegalArgumentException("必须提供 4 个维度 Agent，实际 " + agents.size());
        }
        for (ReviewDimension dim : ReviewDimension.values()) {
            if (!agents.containsKey(dim)) {
                throw new IllegalArgumentException("缺少维度 Agent: " + dim);
            }
        }
    }

    /**
     * 执行完整评审 DAG：PlanChunks → 动态扇出 ReviewChunk × 维度 → ChunkReduce。
     *
     * @param planChunksNode 切片规划节点
     * @param initialContext 初始上下文（taskId 等）
     * @return 所有 ChunkReduceResult（按 chunkId 索引）
     */
    @SuppressWarnings("unchecked")
    public Map<String, ChunkReduceResult> executeReview(Node planChunksNode, NodeContext initialContext)
            throws DagExecutionException {
        Objects.requireNonNull(planChunksNode, "planChunksNode");
        Objects.requireNonNull(initialContext, "initialContext");

        // 1. 执行 PlanChunks 节点
        log.info("任务 {} 开始执行 PlanChunks", initialContext.taskId());
        DagDefinition planDag = DagDefinition.builder()
                .addNode(planChunksNode)
                .build();
        NodeContext afterPlan = scheduler.execute(planDag, initialContext);
        List<ReviewChunk> chunks = (List<ReviewChunk>) afterPlan.completedResults().get(planChunksNode.id());

        if (chunks == null || chunks.isEmpty()) {
            log.warn("任务 {} 切片为空，跳过评审", initialContext.taskId());
            return Map.of();
        }

        FanOut fanOut = fanOutReview(chunks, afterPlan, null);
        Map<String, ChunkReduceResult> results = collectReduceResults(fanOut);
        log.info("任务 {} 评审完成，{} 个 chunk 归约完毕", initialContext.taskId(), results.size());
        return results;
    }

    /**
     * 执行完整的跨会话 Map-Reduce 评审（M6，ARCHITECTURE 6.2）：
     * <pre>
     * BuildChangeMap → PlanChunks → [chunk × 4 维度 并行] → ChunkReduce × N → GlobalReduce
     * </pre>
     *
     * <p>状态流转：{@code PLANNING}（建图 + 切片）→ {@code REVIEWING}（并行评审）→
     * {@code AGGREGATING}（全局归约）。{@code PUBLISHED} 由 M7 回写 PR 时推进。
     *
     * <p>ChangeMap 产出后写入共享状态，之后每个子会话节点都能取到（ARCHITECTURE 6.2 第 1 步）。
     *
     * @param buildChangeMapNode 变更地图节点
     * @param planChunksNode     切片规划节点
     * @param task               评审任务聚合（可为 null，此时不推进状态、不挂结果）
     * @param initialContext     初始上下文（需含共享状态 diffText / newSources）
     */
    @SuppressWarnings("unchecked")
    public CrossSessionReviewResult executeCrossSessionReview(Node buildChangeMapNode, Node planChunksNode,
                                                             ReviewTask task, NodeContext initialContext)
            throws DagExecutionException {
        Objects.requireNonNull(buildChangeMapNode, "buildChangeMapNode");
        Objects.requireNonNull(planChunksNode, "planChunksNode");
        Objects.requireNonNull(initialContext, "initialContext");
        if (globalReduceAgent == null) {
            throw new IllegalStateException("未配置 GlobalReduceAgent，无法执行跨会话评审");
        }

        String taskId = initialContext.taskId();
        advance(task, ReviewTaskStatus.PLANNING);

        // 1. 变更地图 + 切片（地图先行，切片不依赖它，但顺序执行可让切片阶段也拿到地图）
        log.info("任务 {} 开始构建全局变更地图", taskId);
        DagDefinition planDag = DagDefinition.builder()
                .addNode(buildChangeMapNode)
                .addNode(planChunksNode, List.of(buildChangeMapNode.id()))
                .build();
        NodeContext afterPlan = scheduler.execute(planDag, initialContext);

        ChangeMap changeMap = (ChangeMap) afterPlan.completedResults().get(buildChangeMapNode.id());
        List<ReviewChunk> chunks = (List<ReviewChunk>) afterPlan.completedResults().get(planChunksNode.id());
        if (changeMap != null) {
            afterPlan = afterPlan.withSharedState(ReviewChunkNode.CHANGE_MAP_KEY, changeMap);
            log.info("任务 {} 变更地图就绪：{} 个文件、{} 处签名变更（其中 {} 处参数类型变更）{}",
                    taskId, changeMap.files().size(), changeMap.signatureChanges().size(),
                    changeMap.signatureChangesOnly().size(), changeMap.degraded() ? "，LLM 部分降级" : "");
        }

        if (chunks == null || chunks.isEmpty()) {
            log.warn("任务 {} 切片为空，跳过评审", taskId);
            advance(task, ReviewTaskStatus.REVIEWING);
            advance(task, ReviewTaskStatus.AGGREGATING);
            return new CrossSessionReviewResult(changeMap, Map.of(),
                    GlobalReduceResult.degraded("切片为空，无可归约摘要", 0));
        }
        if (task != null) {
            task.assignChunks(chunks);
            if (changeMap != null) {
                task.assignChangeMap(changeMap);
            }
        }

        // 2. 子会话并行评审 + 片级归约
        advance(task, ReviewTaskStatus.REVIEWING);
        FanOut fanOut = fanOutReview(chunks, afterPlan, changeMap);
        Map<String, ChunkReduceResult> chunkResults = collectReduceResults(fanOut);
        if (task != null) {
            chunkResults.values().stream()
                    .filter(ChunkReduceResult::hasSummary)
                    .forEach(r -> task.assignChunkSummary(r.chunkId(), r.summary()));
        }

        // 3. 全局归约：只读摘要
        advance(task, ReviewTaskStatus.AGGREGATING);
        List<String> reduceNodeIds = chunks.stream()
                .map(c -> fanOut.chunkReduceNodeIds().get(c.chunkId()))
                .toList();
        GlobalReduceNode globalNode = new GlobalReduceNode(
                "global-reduce", globalReduceAgent, reduceNodeIds, changeMap);
        DagDefinition.Builder globalBuilder = DagDefinition.builder();
        fanOut.dag().nodes().values().forEach(globalBuilder::addNode);
        fanOut.dag().dependencies().forEach((nodeId, deps) ->
                deps.forEach(dep -> globalBuilder.addDependency(nodeId, dep)));
        globalBuilder.addNode(globalNode, reduceNodeIds);
        NodeContext afterGlobal = scheduler.execute(globalBuilder.build(), fanOut.context());

        GlobalReduceResult globalResult =
                (GlobalReduceResult) afterGlobal.completedResults().get(globalNode.id());
        if (globalResult == null) {
            throw new IllegalStateException("全局归约节点未完成");
        }

        CrossSessionReviewResult result =
                new CrossSessionReviewResult(changeMap, chunkResults, globalResult);
        if (task != null) {
            task.assignFindings(result.allFindings());
        }
        log.info("任务 {} 跨会话评审完成：{} 个切片、{} 条片级意见、{} 条跨文件意见",
                taskId, chunkResults.size(),
                chunkResults.values().stream().mapToInt(r -> r.findings().size()).sum(),
                globalResult.findings().size());
        return result;
    }

    /** 为每个 chunk × 维度建评审节点并执行到片级归约。 */
    private FanOut fanOutReview(List<ReviewChunk> chunks, NodeContext context, ChangeMap changeMap)
            throws DagExecutionException {
        DagDefinition.Builder reviewDagBuilder = DagDefinition.builder();
        Map<String, String> chunkReduceNodeIds = new HashMap<>();  // chunkId -> reduceNodeId

        for (ReviewChunk chunk : chunks) {
            List<String> reviewNodeIds = new ArrayList<>();
            for (ReviewDimension dim : ReviewDimension.values()) {
                String reviewNodeId = "review-" + chunk.chunkId() + "-" + dim.name().toLowerCase();
                ReviewChunkNode reviewNode =
                        new ReviewChunkNode(reviewNodeId, agents.get(dim), chunk, changeMap);
                reviewDagBuilder.addNode(reviewNode);
                reviewNodeIds.add(reviewNodeId);
            }
            String reduceNodeId = "reduce-" + chunk.chunkId();
            reviewDagBuilder.addNode(new ChunkReduceNode(reduceNodeId, chunk.chunkId(), reviewNodeIds),
                    reviewNodeIds);
            chunkReduceNodeIds.put(chunk.chunkId(), reduceNodeId);
        }

        DagDefinition reviewDag = reviewDagBuilder.build();
        log.info("任务 {} 开始并行评审：{} 个 chunk × 4 维度 = {} 个评审节点",
                context.taskId(), chunks.size(), chunks.size() * 4);
        NodeContext afterReview = scheduler.execute(reviewDag, context);
        return new FanOut(reviewDag, afterReview, chunkReduceNodeIds);
    }

    private Map<String, ChunkReduceResult> collectReduceResults(FanOut fanOut) {
        Map<String, ChunkReduceResult> results = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : fanOut.chunkReduceNodeIds().entrySet()) {
            ChunkReduceResult result =
                    (ChunkReduceResult) fanOut.context().completedResults().get(entry.getValue());
            if (result == null) {
                throw new IllegalStateException("归约节点 " + entry.getValue() + " 未完成");
            }
            results.put(entry.getKey(), result);
        }
        return results;
    }

    /** 推进聚合状态并落库；task 为 null 时跳过（纯编排测试场景）。 */
    private void advance(ReviewTask task, ReviewTaskStatus target) {
        if (task == null) {
            return;
        }
        task.transitionTo(target);
        if (taskRepository != null) {
            taskRepository.updateStatus(task.taskId(), target);
        }
        log.info("任务 {} 状态 → {}", task.taskId(), target);
    }

    /** 扇出阶段的产物：拓扑、执行后上下文、chunkId → 归约节点 id。 */
    private record FanOut(DagDefinition dag, NodeContext context, Map<String, String> chunkReduceNodeIds) {
    }
}
