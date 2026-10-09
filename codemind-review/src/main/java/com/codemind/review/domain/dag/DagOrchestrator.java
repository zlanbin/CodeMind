package com.codemind.review.domain.dag;

import com.codemind.review.domain.agent.ReviewAgent;
import com.codemind.review.domain.agent.ReviewDimension;
import com.codemind.review.domain.chunk.ReviewChunk;
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

    /**
     * @param scheduler 拓扑调度器
     * @param agents    各维度 Agent（Style / Security / Perf / Test）
     */
    public DagOrchestrator(DagScheduler scheduler, Map<ReviewDimension, ReviewAgent> agents) {
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
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

        log.info("任务 {} 产出 {} 个 chunk，开始动态扇出评审", initialContext.taskId(), chunks.size());

        // 2. 为每个 chunk × 维度动态创建节点
        DagDefinition.Builder reviewDagBuilder = DagDefinition.builder();
        Map<String, String> chunkReduceNodeIds = new HashMap<>();  // chunkId -> reduceNodeId

        for (ReviewChunk chunk : chunks) {
            List<String> reviewNodeIds = new ArrayList<>();

            // 为 4 个维度各创建一个 ReviewChunkNode
            for (ReviewDimension dim : ReviewDimension.values()) {
                String reviewNodeId = "review-" + chunk.chunkId() + "-" + dim.name().toLowerCase();
                ReviewAgent agent = agents.get(dim);
                ReviewChunkNode reviewNode = new ReviewChunkNode(reviewNodeId, agent, chunk);
                reviewDagBuilder.addNode(reviewNode);
                reviewNodeIds.add(reviewNodeId);
            }

            // 为该 chunk 创建一个 ChunkReduceNode，依赖 4 个 ReviewChunkNode
            String reduceNodeId = "reduce-" + chunk.chunkId();
            ChunkReduceNode reduceNode = new ChunkReduceNode(reduceNodeId, chunk.chunkId(), reviewNodeIds);
            reviewDagBuilder.addNode(reduceNode, reviewNodeIds);
            chunkReduceNodeIds.put(chunk.chunkId(), reduceNodeId);
        }

        DagDefinition reviewDag = reviewDagBuilder.build();

        // 3. 执行评审 DAG（虚拟线程并行）
        log.info("任务 {} 开始并行评审：{} 个 chunk × 4 维度 = {} 个评审节点",
                initialContext.taskId(), chunks.size(), chunks.size() * 4);
        NodeContext afterReview = scheduler.execute(reviewDag, afterPlan);

        // 4. 提取所有 ChunkReduceResult
        Map<String, ChunkReduceResult> results = new HashMap<>();
        for (Map.Entry<String, String> entry : chunkReduceNodeIds.entrySet()) {
            String chunkId = entry.getKey();
            String reduceNodeId = entry.getValue();
            ChunkReduceResult result = (ChunkReduceResult) afterReview.completedResults().get(reduceNodeId);
            if (result == null) {
                throw new IllegalStateException("归约节点 " + reduceNodeId + " 未完成");
            }
            results.put(chunkId, result);
        }

        log.info("任务 {} 评审完成，{} 个 chunk 归约完毕", initialContext.taskId(), results.size());
        return results;
    }
}
