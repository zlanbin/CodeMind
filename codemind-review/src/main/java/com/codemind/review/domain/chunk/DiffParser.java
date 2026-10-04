package com.codemind.review.domain.chunk;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 解析 unified diff（git 风格）为 {@link DiffFile} 列表。纯 Java，无外部依赖。
 *
 * <p>原计划用 java-diff-utils，实测其 {@code UnifiedDiffReader} 在真实 GitHub diff 上不可靠
 * （单文件 EOF 丢最后一个 hunk、多文件时后一个文件头被当成前一个 hunk 的上下文行静默串接），
 * 故自实现。只做评审需要的部分：文件路径、变更类型、hunk 行号区间、原始 diff 行。
 *
 * <p>支持的头：{@code diff --git}、{@code --- / +++}、{@code new file mode}、
 * {@code deleted file mode}、{@code rename from/to}、{@code @@ -a,b +c,d @@}。
 * 二进制文件（{@code Binary files ... differ}）跳过（无 hunk）。
 */
public final class DiffParser {

    private static final Pattern HUNK_HEADER =
            Pattern.compile("^@@ -(\\d+)(?:,(\\d+))? \\+(\\d+)(?:,(\\d+))? @@.*$");

    /** 解析整个 unified diff 文本。空文本返回空列表。 */
    public List<DiffFile> parse(String unifiedDiff) {
        if (unifiedDiff == null || unifiedDiff.isBlank()) {
            return List.of();
        }
        String[] lines = unifiedDiff.split("\n", -1);
        List<DiffFile> files = new ArrayList<>();
        FileAccumulator current = null;

        for (int i = 0; i < lines.length; i++) {
            String line = stripCr(lines[i]);

            if (line.startsWith("diff --git")) {
                current = flushAndStart(files, current, parseGitPaths(line));
                continue;
            }
            // 没有 diff --git 头的裸 unified diff：--- 开启一个新文件
            if (line.startsWith("--- ") && (current == null || current.hasHunks())) {
                current = flushAndStart(files, current, new String[]{null, null});
            }
            if (current == null) {
                continue; // 文件头之前的内容（如 commit 信息）跳过
            }

            if (line.startsWith("new file mode")) {
                current.changeType = ChangeType.ADDED;
            } else if (line.startsWith("deleted file mode")) {
                current.changeType = ChangeType.DELETED;
            } else if (line.startsWith("rename from ")) {
                current.oldPath = line.substring("rename from ".length()).trim();
                current.changeType = ChangeType.RENAMED;
            } else if (line.startsWith("rename to ")) {
                current.newPath = line.substring("rename to ".length()).trim();
                current.changeType = ChangeType.RENAMED;
            } else if (line.startsWith("--- ")) {
                current.oldPath = stripAbPrefix(line.substring(4).trim());
            } else if (line.startsWith("+++ ")) {
                current.newPath = stripAbPrefix(line.substring(4).trim());
            } else if (line.startsWith("@@")) {
                Matcher m = HUNK_HEADER.matcher(line);
                if (m.matches()) {
                    i = consumeHunk(current, m, lines, i);
                }
            }
            // 其它头行（index、similarity index、Binary files 等）忽略
        }
        flush(files, current);
        return List.copyOf(files);
    }

    /** 读完一个 hunk 的 body，返回最后消费到的行索引。 */
    private int consumeHunk(FileAccumulator file, Matcher header, String[] lines, int headerIdx) {
        int oldStart = Integer.parseInt(header.group(1));
        int oldCount = header.group(2) == null ? 1 : Integer.parseInt(header.group(2));
        int newStart = Integer.parseInt(header.group(3));
        int newCount = header.group(4) == null ? 1 : Integer.parseInt(header.group(4));

        List<String> body = new ArrayList<>();
        int oldSeen = 0;
        int newSeen = 0;
        int i = headerIdx + 1;
        for (; i < lines.length; i++) {
            String raw = stripCr(lines[i]);
            // hunk 在计满 old/new 行数后结束；之后遇到的任何头行都归属下一段
            if (oldSeen >= oldCount && newSeen >= newCount) {
                break;
            }
            if (raw.isEmpty()) {
                // 空行：unified diff 中代表一行空的上下文行（前导空格可能被编辑器裁掉）
                body.add(" ");
                oldSeen++;
                newSeen++;
                continue;
            }
            char c = raw.charAt(0);
            switch (c) {
                case ' ' -> { body.add(raw); oldSeen++; newSeen++; }
                case '+' -> { body.add(raw); newSeen++; }
                case '-' -> { body.add(raw); oldSeen++; }
                case '\\' -> body.add(raw); // "\ No newline at end of file"
                default -> {
                    // 不属于 hunk 的行（下一个文件头 / @@），结束本 hunk，回退一行让主循环处理
                    i--;
                    file.hunks.add(new DiffHunk(oldStart, oldCount, newStart, newCount, body));
                    return i;
                }
            }
        }
        file.hunks.add(new DiffHunk(oldStart, oldCount, newStart, newCount, body));
        return i - 1;
    }

    private FileAccumulator flushAndStart(List<DiffFile> files, FileAccumulator current, String[] paths) {
        flush(files, current);
        FileAccumulator acc = new FileAccumulator();
        acc.oldPath = paths[0];
        acc.newPath = paths[1];
        return acc;
    }

    private void flush(List<DiffFile> files, FileAccumulator acc) {
        if (acc == null) {
            return;
        }
        files.add(acc.toDiffFile());
    }

    /** 从 {@code diff --git a/x b/y} 抽出 old/new 路径（去 a/ b/ 前缀）。 */
    private String[] parseGitPaths(String line) {
        String rest = line.substring("diff --git".length()).trim();
        int split = findPathSplit(rest);
        if (split < 0) {
            return new String[]{null, null};
        }
        return new String[]{
                stripAbPrefix(rest.substring(0, split).trim()),
                stripAbPrefix(rest.substring(split + 1).trim())
        };
    }

    /** {@code a/path b/path}：以 " b/" 为界切分，容忍路径里有空格。 */
    private int findPathSplit(String rest) {
        int idx = rest.indexOf(" b/");
        return idx >= 0 ? idx : rest.indexOf(' ');
    }

    private static String stripAbPrefix(String path) {
        if (path.startsWith("a/") || path.startsWith("b/")) {
            return path.substring(2);
        }
        return path;
    }

    private static String stripCr(String line) {
        return line.endsWith("\r") ? line.substring(0, line.length() - 1) : line;
    }

    /** 累积一个文件的解析中间态，最后冻结成 {@link DiffFile}。 */
    private static final class FileAccumulator {
        String oldPath;
        String newPath;
        ChangeType changeType;
        final List<DiffHunk> hunks = new ArrayList<>();

        boolean hasHunks() {
            return !hunks.isEmpty();
        }

        DiffFile toDiffFile() {
            String devNull = "/dev/null";
            boolean added = devNull.equals(oldPath);
            boolean deleted = devNull.equals(newPath);
            String resolvedOld = (oldPath == null || added) ? newPath : oldPath;
            String resolvedNew = (newPath == null || deleted) ? oldPath : newPath;
            ChangeType type = changeType;
            if (type == null) {
                if (added) {
                    type = ChangeType.ADDED;
                } else if (deleted) {
                    type = ChangeType.DELETED;
                } else {
                    type = ChangeType.MODIFIED;
                }
            }
            return new DiffFile(resolvedOld, resolvedNew, type, hunks);
        }
    }
}
