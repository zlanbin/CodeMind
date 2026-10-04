package com.codemind.review.domain.chunk;

import java.util.List;
import java.util.Objects;

/**
 * 语义切片：一个 chunk 对应一个 LLM 子会话的 diff 输入（ARCHITECTURE 4、6.2）。
 *
 * <p>切片保证不截断方法：Java 文件按 AST 方法边界切，非 Java 按 hunk 切。
 * 单个方法本身就超 diff 预算时，仍整片输出并置 {@link #oversized()}，由下游降级
 * （裁剪上下文代码 → 压缩记忆 → 进一步拆分，ARCHITECTURE 6.3），切片阶段不截断、不丢弃。
 *
 * @param chunkId        片标识
 * @param files          本片覆盖的文件路径（跨文件 chunk 可能多个）
 * @param changedSymbols 本片涉及的变更符号（Java 为方法限定名，如 {@code OrderService#cancel}）
 * @param diffContent    本片的 unified diff 文本（拼接后的原始 diff 行）
 * @param tokenEstimate  diffContent 的 token 估算
 * @param oversized      单片即超 diff 预算（退化场景）
 */
public record ReviewChunk(
        String chunkId,
        List<String> files,
        List<String> changedSymbols,
        String diffContent,
        int tokenEstimate,
        boolean oversized) {

    public ReviewChunk {
        chunkId = Objects.requireNonNull(chunkId, "chunkId");
        files = List.copyOf(Objects.requireNonNull(files, "files"));
        changedSymbols = List.copyOf(Objects.requireNonNull(changedSymbols, "changedSymbols"));
        diffContent = Objects.requireNonNull(diffContent, "diffContent");
        if (tokenEstimate < 0) {
            throw new IllegalArgumentException("tokenEstimate 不能为负");
        }
    }
}
