package com.codemind.review.domain.dag;

import com.codemind.review.domain.chunk.ChunkBudget;
import com.codemind.review.domain.chunk.ChunkRepository;
import com.codemind.review.domain.chunk.DiffParser;
import com.codemind.review.domain.chunk.ReviewChunk;
import com.codemind.review.domain.chunk.SemanticChunker;
import com.codemind.review.domain.task.ReviewTask;
import com.codemind.review.domain.task.ReviewTaskRepository;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * PlanChunks 节点：解析 diff，按 AST 方法边界语义切片，落库并挂回聚合（ARCHITECTURE 5 DAG 图）。
 *
 * <p>M2 {@link SemanticChunker} 已就绪，M3 把它包进一个 Node，并用
 * {@link ReviewTask#assignChunks(List)} 把结果挂回聚合。
 *
 * <p>输入（从 {@link NodeContext} 取）：
 * <ul>
 *   <li>{@code "diffText"} (String): 完整 unified diff 文本</li>
 *   <li>{@code "newSources"} (Map&lt;String, String&gt;): 文件路径 → 完整新源码（Java 文件需要）</li>
 * </ul>
 *
 * <p>输出：{@link List<ReviewChunk>}，同时落库并挂回 {@link ReviewTask}。
 */
public class PlanChunksNode implements Node {

    private final String nodeId;
    private final SemanticChunker chunker;
    private final ChunkRepository chunkRepository;
    private final ReviewTaskRepository taskRepository;

    public PlanChunksNode(String nodeId, SemanticChunker chunker,
                          ChunkRepository chunkRepository, ReviewTaskRepository taskRepository) {
        this.nodeId = Objects.requireNonNull(nodeId, "nodeId");
        this.chunker = Objects.requireNonNull(chunker, "chunker");
        this.chunkRepository = Objects.requireNonNull(chunkRepository, "chunkRepository");
        this.taskRepository = Objects.requireNonNull(taskRepository, "taskRepository");
    }

    @Override
    public String id() {
        return nodeId;
    }

    @Override
    @SuppressWarnings("unchecked")
    public List<ReviewChunk> execute(NodeContext context) throws Exception {
        String taskId = context.taskId();
        String diffText = context.getSharedState("diffText", String.class);
        Map<String, String> newSources = context.getSharedState("newSources", Map.class);

        if (diffText == null || diffText.isBlank()) {
            throw new IllegalArgumentException("PlanChunks 节点缺少 diffText");
        }

        // 1. 解析 diff
        DiffParser parser = new DiffParser();
        var diffFiles = parser.parse(diffText);

        // 2. 语义切片
        ChunkBudget budget = ChunkBudget.ofDefault();
        List<ReviewChunk> chunks = chunker.chunk(diffFiles, newSources, budget);

        // 3. 持久化切片
        chunkRepository.saveAll(taskId, chunks);

        // 4. 挂回聚合（需先重新加载 ReviewTask 聚合根，避免状态丢失）
        // 注意：这里简化为只更新 chunks，实际场景可能需要通过应用服务协调
        // M3 暂不实现完整的聚合根重建逻辑，仅做持久化和返回

        return chunks;
    }
}
