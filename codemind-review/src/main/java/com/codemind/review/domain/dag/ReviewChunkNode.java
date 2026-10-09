package com.codemind.review.domain.dag;

import com.codemind.review.domain.agent.AgentReview;
import com.codemind.review.domain.agent.ReviewAgent;
import com.codemind.review.domain.chunk.ReviewChunk;

import java.util.Objects;

/**
 * 单 chunk 评审节点（ARCHITECTURE 5 子会话评审，M4 单 Agent 版）。
 *
 * <p>把 {@link ReviewAgent} 对一个 {@link ReviewChunk} 的评审包成一个 DAG 节点，衔接
 * {@link PlanChunksNode} 产出的切片。M5 多 Agent 并行时，每个 chunk 会 fan-out 成多个本节点。
 *
 * <p>输出 {@link AgentReview}（Finding 列表或降级结果），由调度器写入 checkpoint。
 * 节点自身不抛「评审失败」——{@link ReviewAgent} 已把重试/降级消化成 {@code degraded} 结果。
 */
public final class ReviewChunkNode implements Node {

    private final String nodeId;
    private final ReviewAgent agent;
    private final ReviewChunk chunk;

    public ReviewChunkNode(String nodeId, ReviewAgent agent, ReviewChunk chunk) {
        this.nodeId = Objects.requireNonNull(nodeId, "nodeId");
        this.agent = Objects.requireNonNull(agent, "agent");
        this.chunk = Objects.requireNonNull(chunk, "chunk");
    }

    @Override
    public String id() {
        return nodeId;
    }

    @Override
    public AgentReview execute(NodeContext context) {
        return agent.review(chunk);
    }
}
