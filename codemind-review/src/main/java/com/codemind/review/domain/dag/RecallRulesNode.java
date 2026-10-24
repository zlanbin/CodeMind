package com.codemind.review.domain.dag;

import com.codemind.review.domain.chunk.ReviewChunk;
import com.codemind.review.domain.rule.RecalledRule;
import com.codemind.review.domain.rule.RuleRecallException;
import com.codemind.review.domain.rule.RuleRecallPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Objects;

/**
 * 规则召回节点（M11）：按 chunk 粒度调用一次 {@link RuleRecallPort}，4 个评审维度
 * 共享同一次召回结果（不重复召回 4 次——查询文本相同，没必要分别调用）。
 *
 * <p>召回失败 / 规则服务不可用 / 无候选 → 静默返回空列表，不记入 degradations：
 * 规则缺失不代表评审质量下降（本来就可能没有匹配的规则条款），与 {@code FindingFilter}
 * 「过滤失败必须放行且要可见」不同严重级别（M11 D10）。
 */
public final class RecallRulesNode implements Node {

    private static final Logger log = LoggerFactory.getLogger(RecallRulesNode.class);
    private static final int TOP_K = 5;

    private final String nodeId;
    private final RuleRecallPort port;
    private final String project;
    private final ReviewChunk chunk;

    public RecallRulesNode(String nodeId, RuleRecallPort port, String project, ReviewChunk chunk) {
        this.nodeId = Objects.requireNonNull(nodeId, "nodeId");
        this.port = Objects.requireNonNull(port, "port");
        this.project = Objects.requireNonNull(project, "project");
        this.chunk = Objects.requireNonNull(chunk, "chunk");
    }

    @Override
    public String id() {
        return nodeId;
    }

    @Override
    public List<RecalledRule> execute(NodeContext context) {
        String query = buildQuery();
        try {
            return port.recall(project, query, TOP_K);
        } catch (RuleRecallException e) {
            log.debug("chunk {} 规则召回失败，静默降级为空列表: {}", chunk.chunkId(), e.getMessage());
            return List.of();
        }
    }

    private String buildQuery() {
        return String.join(" ", chunk.changedSymbols()) + " " + String.join(" ", chunk.files());
    }
}
