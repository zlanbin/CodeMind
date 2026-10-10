package com.codemind.review.domain.dag;

import com.codemind.review.domain.agent.ChunkSummary;
import com.codemind.review.domain.agent.GlobalReduceAgent;
import com.codemind.review.domain.agent.GlobalReduceResult;
import com.codemind.review.domain.changemap.ChangeMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 全局归约节点（ARCHITECTURE 5 DAG 图的 GlobalReduce、6.2 第 4 步）。
 *
 * <p>依赖所有 {@link ChunkReduceNode}，从它们的 {@link ChunkReduceResult} 里取出各片摘要，
 * 连同 {@link ChangeMap} 一起交给 {@link GlobalReduceAgent}。<b>不读原始 diff</b>。
 *
 * <p>摘要缺失的片会被跳过并记日志：某片摘要降级不该让全局归约整体失效（其余片仍可比对）。
 * 全部片都没有可用摘要时，Agent 内部降级为空结果。
 *
 * <p>输出 {@link GlobalReduceResult}；Finding 的合并由 {@link DagOrchestrator} 做
 * ——追加到任务级 Findings，不额外加 MergeFindings 节点。
 */
public final class GlobalReduceNode implements Node {

    private static final Logger log = LoggerFactory.getLogger(GlobalReduceNode.class);

    private final String nodeId;
    private final GlobalReduceAgent agent;
    private final List<String> chunkReduceNodeIds;
    private final ChangeMap changeMap;

    public GlobalReduceNode(String nodeId, GlobalReduceAgent agent, List<String> chunkReduceNodeIds) {
        this(nodeId, agent, chunkReduceNodeIds, null);
    }

    public GlobalReduceNode(String nodeId, GlobalReduceAgent agent, List<String> chunkReduceNodeIds,
                            ChangeMap changeMap) {
        this.nodeId = Objects.requireNonNull(nodeId, "nodeId");
        this.agent = Objects.requireNonNull(agent, "agent");
        this.chunkReduceNodeIds = List.copyOf(chunkReduceNodeIds);
        if (chunkReduceNodeIds.isEmpty()) {
            throw new IllegalArgumentException("chunkReduceNodeIds 不能为空");
        }
        this.changeMap = changeMap;
    }

    @Override
    public String id() {
        return nodeId;
    }

    @Override
    public GlobalReduceResult execute(NodeContext context) {
        List<ChunkSummary> summaries = new ArrayList<>();
        for (String sourceId : chunkReduceNodeIds) {
            Object result = context.completedResults().get(sourceId);
            if (result == null) {
                throw new IllegalStateException("上游归约节点 " + sourceId + " 未完成");
            }
            if (!(result instanceof ChunkReduceResult reduceResult)) {
                throw new IllegalStateException(
                        "上游节点 " + sourceId + " 结果类型非法: " + result.getClass());
            }
            if (reduceResult.summary() == null) {
                log.warn("切片 {} 无摘要，全局归约跳过该片", reduceResult.chunkId());
                continue;
            }
            summaries.add(reduceResult.summary());
        }

        ChangeMap effective = changeMap != null
                ? changeMap
                : context.getSharedState(ReviewChunkNode.CHANGE_MAP_KEY, ChangeMap.class);

        log.info("全局归约开始：{} 份可用摘要（共 {} 个切片）", summaries.size(), chunkReduceNodeIds.size());
        return agent.reduce(effective, summaries);
    }
}
