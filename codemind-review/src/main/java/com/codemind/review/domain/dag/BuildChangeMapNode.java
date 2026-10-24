package com.codemind.review.domain.dag;

import com.codemind.review.domain.changemap.ChangeMap;
import com.codemind.review.domain.changemap.ChangeMapAgent;
import com.codemind.review.domain.changemap.FileStat;
import com.codemind.review.domain.chunk.ChangedSignatureExtractor;
import com.codemind.review.domain.chunk.DiffFile;
import com.codemind.review.domain.chunk.DiffParser;
import com.codemind.review.domain.chunk.SignatureChange;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 全局变更地图节点（ARCHITECTURE 5 DAG 图的 BuildChangeMap、6.2 第 1 步）。
 *
 * <p>输入（从 {@link NodeContext} 的共享状态取，与 {@link PlanChunksNode} 一致）：
 * <ul>
 *   <li>{@code "diffText"} (String): 完整 unified diff 文本</li>
 *   <li>{@code "newSources"} (Map&lt;String, String&gt;): 文件路径 → 完整新源码（提取签名需要）</li>
 * </ul>
 *
 * <p>节点内部：解析 diff → 统计增删行数 → AST 提取方法签名变更 → 调 {@link ChangeMapAgent}
 * 产出宏观判断。<b>不把 diff 内容投给 LLM</b>，只给文件列表 + 签名变更 + 统计
 * （M6 设计审查「修正假设 A」）。
 *
 * <p>输出：{@link ChangeMap}。LLM 失败时 Agent 内部已降级，本节点不抛异常——
 * 变更地图缺宏观判断不该让整个 PR 评审失败，确定性的签名变更仍会传给下游。
 */
public final class BuildChangeMapNode implements Node {

    private final String nodeId;
    private final ChangeMapAgent agent;
    private final ChangedSignatureExtractor signatureExtractor;

    public BuildChangeMapNode(String nodeId, ChangeMapAgent agent) {
        this(nodeId, agent, new ChangedSignatureExtractor());
    }

    public BuildChangeMapNode(String nodeId, ChangeMapAgent agent, ChangedSignatureExtractor signatureExtractor) {
        this.nodeId = Objects.requireNonNull(nodeId, "nodeId");
        this.agent = Objects.requireNonNull(agent, "agent");
        this.signatureExtractor = Objects.requireNonNull(signatureExtractor, "signatureExtractor");
    }

    @Override
    public String id() {
        return nodeId;
    }

    @Override
    public String nodeType() {
        return "BUILD_CHANGE_MAP";
    }

    @Override
    @SuppressWarnings("unchecked")
    public ChangeMap execute(NodeContext context) {
        String diffText = context.getSharedState("diffText", String.class);
        if (diffText == null || diffText.isBlank()) {
            throw new IllegalArgumentException("BuildChangeMap 节点缺少 diffText");
        }
        Map<String, String> newSources = context.getSharedState("newSources", Map.class);

        List<DiffFile> files = new DiffParser().parse(diffText);
        List<FileStat> stats = FileStat.from(files);
        List<SignatureChange> signatureChanges = signatureExtractor.extract(files, newSources);

        return agent.build(stats, signatureChanges);
    }
}
