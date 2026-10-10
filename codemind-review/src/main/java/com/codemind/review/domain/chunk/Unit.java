package com.codemind.review.domain.chunk;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 切片中间态：一个「不可分割」的变更单元——一个方法的全部 hunk，或降级时的单个 hunk。
 * 可被合并（{@link #absorb}）但<b>原子单元本身不会被拆开</b>，由此保证方法不被截断。
 *
 * <p>M6 起记录合并构成（{@link #constituents()}）：跨文件聚合出来的 unit 若整体超 diff 预算，
 * 装箱阶段可以沿合并缝拆回原子单元（ARCHITECTURE 6.3 降级链的最后一步「拆分 chunk」）。
 * 拆的是聚合关系，不是方法——单个原子单元超预算时仍整片输出并标 {@code oversized}。
 */
final class Unit {

    final DiffFile file;
    final List<DiffHunk> hunks;
    final Set<String> methodNames; // Java 方法限定名，如 OrderService#cancel(int)；降级 unit 为空
    /** 合并吸收进来的其它 unit（含同文件与跨文件），保留原子边界以便必要时拆回。 */
    private final List<Unit> absorbed = new ArrayList<>();

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
        for (Unit e : absorbed) {
            all.addAll(e.methodNames());
        }
        return all;
    }

    /** 本 unit 覆盖的所有文件路径（含合并吸收进来的），保持出现顺序。 */
    List<String> filePaths() {
        List<String> paths = new ArrayList<>();
        paths.add(file.path());
        for (Unit e : absorbed) {
            for (String p : e.filePaths()) {
                if (!paths.contains(p)) {
                    paths.add(p);
                }
            }
        }
        return paths;
    }

    /** 吸收另一个 unit（调用关系合并）。只记挂载关系，不并 hunk，保留原子边界。 */
    void absorb(Unit other) {
        absorbed.add(other);
    }

    /**
     * 展开为原子单元列表（自身在前，吸收进来的在后，递归展开）。
     * 原子单元 = 一个方法的全部 hunk（或降级时的单个 hunk），不可再分。
     */
    List<Unit> constituents() {
        List<Unit> parts = new ArrayList<>();
        parts.add(new Unit(file, hunks, methodNames));
        for (Unit e : absorbed) {
            parts.addAll(e.constituents());
        }
        return parts;
    }

    /** 是否由多个原子单元聚合而成——只有这种才能沿合并缝拆开。 */
    boolean isSplittable() {
        return !absorbed.isEmpty();
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
        // 按文件归组（保持首次出现顺序），同文件的 hunk 合在一个头下
        Map<String, List<DiffHunk>> byFile = new LinkedHashMap<>();
        Map<String, DiffFile> files = new LinkedHashMap<>();
        for (Unit part : constituents()) {
            String path = part.file.path();
            files.putIfAbsent(path, part.file);
            byFile.computeIfAbsent(path, k -> new ArrayList<>()).addAll(part.hunks);
        }

        StringBuilder sb = new StringBuilder();
        byFile.forEach((path, hunkList) -> renderFile(sb, files.get(path), hunkList));
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
