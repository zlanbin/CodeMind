package com.codemind.review.domain.chunk;

import java.util.List;
import java.util.Objects;

/**
 * 一个 diff hunk：{@code @@ -oldStart,oldCount +newStart,newCount @@}。
 *
 * <p>{@code lines} 保留 unified diff 的原始行（含前导 {@code ' '} / {@code '+'} / {@code '-'}），
 * 用于估算 token 和回写时定位。新增行的实际源码行号从 {@code newStart} 顺序推算。
 */
public record DiffHunk(int oldStart, int oldCount, int newStart, int newCount, List<String> lines) {

    public DiffHunk {
        if (oldStart < 0 || newStart < 0) {
            throw new IllegalArgumentException("hunk 起始行不能为负");
        }
        lines = List.copyOf(Objects.requireNonNull(lines, "lines"));
    }

    /** 本 hunk 覆盖的新文件行号闭区间 {@code [newStart, newStart + newCount - 1]}；newCount 为 0 时表示纯删除。 */
    public boolean coversNewLine(int newLine) {
        return newCount > 0 && newLine >= newStart && newLine <= newStart + newCount - 1;
    }
}
