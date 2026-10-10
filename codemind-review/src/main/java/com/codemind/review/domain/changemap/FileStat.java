package com.codemind.review.domain.changemap;

import com.codemind.review.domain.chunk.ChangeType;
import com.codemind.review.domain.chunk.DiffFile;
import com.codemind.review.domain.chunk.DiffHunk;

import java.util.List;
import java.util.Objects;

/**
 * 单个变更文件的 diff 统计（M6，ChangeMap 的轻量输入）：只有路径、变更类型、增删行数，
 * <b>不含 diff 内容</b>——ChangeMap 是轻量会话，不投喂 diff 本体（ARCHITECTURE 6.2 第 1 步）。
 */
public record FileStat(String path, ChangeType changeType, int addedLines, int deletedLines) {

    public FileStat {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(changeType, "changeType");
    }

    public static FileStat from(DiffFile file) {
        int added = 0;
        int deleted = 0;
        for (DiffHunk hunk : file.hunks()) {
            for (String line : hunk.lines()) {
                if (line.isEmpty()) {
                    continue;
                }
                if (line.charAt(0) == '+') {
                    added++;
                } else if (line.charAt(0) == '-') {
                    deleted++;
                }
            }
        }
        return new FileStat(file.path(), file.changeType(), added, deleted);
    }

    public static List<FileStat> from(List<DiffFile> files) {
        return files.stream().map(FileStat::from).toList();
    }

    /** 一行描述，用于 prompt。 */
    public String describe() {
        return "%s [%s] +%d/-%d".formatted(path, changeType, addedLines, deletedLines);
    }
}
