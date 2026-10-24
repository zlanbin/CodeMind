package com.codemind.review.domain.dag;

import com.codemind.review.domain.agent.AgentReview;
import com.codemind.review.domain.agent.ReviewAgent;
import com.codemind.review.domain.changemap.ChangeMap;
import com.codemind.review.domain.chunk.ReviewChunk;
import com.codemind.review.domain.rule.RecalledRule;

import java.util.List;
import java.util.Objects;

/**
 * 单 chunk 评审节点（ARCHITECTURE 5 子会话评审）。
 *
 * <p>把 {@link ReviewAgent} 对一个 {@link ReviewChunk} 的评审包成一个 DAG 节点，衔接
 * {@link PlanChunksNode} 产出的切片。M5 多 Agent 并行时，每个 chunk 会 fan-out 成多个本节点。
 *
 * <p>M6 起可注入 {@link ChangeMap}（ARCHITECTURE 6.2「之后每个子会话都注入它」）；
 * 构造时没给的话，退回从共享状态键 {@code "changeMap"} 取，都没有则按 M5 行为（不注入）。
 *
 * <p>M11 起可传入 {@code recallRulesNodeId}：执行时从上游 {@link RecallRulesNode} 的结果
 * 取 {@code List<RecalledRule>} 传给 Agent（4 个维度共享同一次召回结果）；不传或上游结果缺失
 * 时传空列表，不影响既有行为。
 *
 * <p>输出 {@link AgentReview}（Finding 列表 + {@code ChunkSummary}，或降级结果），
 * 由调度器写入 checkpoint。节点自身不抛「评审失败」——{@link ReviewAgent} 已把重试/降级
 * 消化成 {@code degraded} 结果。
 */
public final class ReviewChunkNode implements Node {

    /** 共享状态里 ChangeMap 的键。 */
    public static final String CHANGE_MAP_KEY = "changeMap";

    private final String nodeId;
    private final ReviewAgent agent;
    private final ReviewChunk chunk;
    private final ChangeMap changeMap;
    private final String recallRulesNodeId;

    public ReviewChunkNode(String nodeId, ReviewAgent agent, ReviewChunk chunk) {
        this(nodeId, agent, chunk, null, null);
    }

    public ReviewChunkNode(String nodeId, ReviewAgent agent, ReviewChunk chunk, ChangeMap changeMap) {
        this(nodeId, agent, chunk, changeMap, null);
    }

    public ReviewChunkNode(String nodeId, ReviewAgent agent, ReviewChunk chunk, ChangeMap changeMap,
                           String recallRulesNodeId) {
        this.nodeId = Objects.requireNonNull(nodeId, "nodeId");
        this.agent = Objects.requireNonNull(agent, "agent");
        this.chunk = Objects.requireNonNull(chunk, "chunk");
        this.changeMap = changeMap;
        this.recallRulesNodeId = recallRulesNodeId;
    }

    @Override
    public String id() {
        return nodeId;
    }

    @SuppressWarnings("unchecked")
    @Override
    public AgentReview execute(NodeContext context) {
        ChangeMap effective = changeMap != null
                ? changeMap
                : context.getSharedState(CHANGE_MAP_KEY, ChangeMap.class);
        List<RecalledRule> rules = recallRulesNodeId == null
                ? List.of()
                : (List<RecalledRule>) context.completedResults().getOrDefault(recallRulesNodeId, List.of());
        return agent.review(chunk, effective, rules);
    }
}
