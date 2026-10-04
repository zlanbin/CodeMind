package com.codemind.review.domain.chunk;

import java.util.List;
import java.util.Objects;

/**
 * 一个变更文件在 diff 中的解析结果：路径、变更类型、全部 hunk。
 *
 * <p>{@code oldPath} 仅在重命名时与 {@code newPath} 不同；删除文件时 {@code newPath} 取 old 路径。
 */
public record DiffFile(String oldPath, String newPath, ChangeType changeType, List<DiffHunk> hunks) {

    public DiffFile {
        Objects.requireNonNull(changeType, "changeType");
        newPath = requireText(newPath, "newPath");
        oldPath = requireText(oldPath, "oldPath");
        hunks = List.copyOf(Objects.requireNonNull(hunks, "hunks"));
    }

    /** 评审以新文件视角为主，路径取 newPath。 */
    public String path() {
        return newPath;
    }

    public boolean isJava() {
        return newPath.endsWith(".java");
    }

    /** 本文件所有 hunk 的 diff 行加总（含 {@code @@} 头的行数估算用 lines）。 */
    public int totalDiffLines() {
        return hunks.stream().mapToInt(h -> h.lines().size()).sum();
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " 不能为空");
        }
        return value;
    }
}
