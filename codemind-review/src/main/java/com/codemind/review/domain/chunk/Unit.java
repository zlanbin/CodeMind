package com.codemind.review.domain.chunk;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 切片中间态：一个「不可分割」的变更单元——一个方法的全部 hunk，或降级时的单个 hunk。
 * 可被合并（{@link #absorb}）但不会被拆开，由此保证方法不被截断。
 */
final class Unit {

    final DiffFile file;
    final List<DiffHunk> hunks;
    final Set<String> methodNames; // Java 方法限定名，如 OrderService#cancel；降级 unit 为空
    private final List<Unit> extraFiles = new ArrayList<>(); // 跨文件合并后吸收进来的其它文件 unit

    Unit(DiffFile file, List<DiffHunk> hunks, Set<String> methodNames) {
        this.file = file;
        this.hunks = hunks;
        this.methodNames = methodNames;
    }

    boolean sameFile(Unit other) {
        return file.path().equals(other.file.path());
    }

    Set<String> methodNames() {
        Set<String> all = new LinkedHashSet<>(methodNames);
        for (Unit e : extraFiles) {
            all.addAll(e.methodNames());
        }
        return all;
    }

    /** 本 unit 覆盖的所有文件路径（含跨文件合并吸收进来的），保持出现顺序。 */
    List<String> filePaths() {
        List<String> paths = new ArrayList<>();
        paths.add(file.path());
        for (Unit e : extraFiles) {
            if (!paths.contains(e.file.path())) {
                paths.add(e.file.path());
            }
        }
        return paths;
    }

    /** 吸收另一个 unit（跨文件合并）。同文件的 unit 合 hunk，异文件的挂到 extraFiles。 */
    void absorb(Unit other) {
        if (sameFile(other)) {
            hunks.addAll(other.hunks);
            methodNames.addAll(other.methodNames);
        } else {
            extraFiles.add(other);
        }
    }

    /** 本 unit 的新增行 + 上下文行文本（用于调用关系匹配，不含被删除行）。 */
    String addedAndContextText() {
        StringBuilder sb = new StringBuilder();
        for (DiffHunk h : hunks) {
            for (String line : h.lines()) {
                if (!line.isEmpty() && line.charAt(0) != '-') {
                    sb.append(line.length() > 0 ? line.substring(1) : line).append('\n');
                }
            }
        }
        return sb.toString();
    }

    /** 渲染为可投喂 LLM 的 unified diff 片段：每个文件一个头 + 其 hunk（按新行号排序）。 */
    String render() {
        StringBuilder sb = new StringBuilder();
        renderFile(sb, file, hunks);
        for (Unit e : extraFiles) {
            renderFile(sb, e.file, e.hunks);
        }
        return sb.toString();
    }

    private static void renderFile(StringBuilder sb, DiffFile file, List<DiffHunk> hunks) {
        sb.append("--- a/").append(file.oldPath()).append('\n');
        sb.append("+++ b/").append(file.newPath()).append('\n');
        hunks.stream()
                .sorted((x, y) -> Integer.compare(x.newStart(), y.newStart()))
                .forEach(h -> {
                    sb.append("@@ -").append(h.oldStart()).append(',').append(h.oldCount())
                            .append(" +").append(h.newStart()).append(',').append(h.newCount())
                            .append(" @@\n");
                    for (String line : h.lines()) {
                        sb.append(line).append('\n');
                    }
                });
    }
}
