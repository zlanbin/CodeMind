package com.codemind.rule.domain.rule;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 按 Markdown 标题层级切块（M11 D3，纯函数，不依赖 Spring）。
 *
 * <p>每个标题到下一个同级或更高级标题前的正文拼成一块；{@code sectionPath} 用标题文本按层级
 * 拼接祖先链（{@code " > "} 分隔）。空块（标题后无正文）与文档开头无标题的正文一律跳过。
 */
public final class RuleDocumentChunker {

    private static final Pattern HEADING = Pattern.compile("^(#{1,6})\\s+(.+)$");

    public List<RuleChunk> chunk(String project, long documentId, String markdown) {
        List<RuleChunk> result = new ArrayList<>();
        // 栈元素：[level, headingText]；用于按层级拼 sectionPath，同级/更高级标题出现时弹出
        Deque<int[]> levelStack = new ArrayDeque<>();
        List<String> pathStack = new ArrayList<>();

        String currentHeading = null;
        StringBuilder currentBody = new StringBuilder();

        for (String line : markdown.split("\n", -1)) {
            Matcher m = HEADING.matcher(line.stripTrailing());
            if (m.matches()) {
                flush(result, project, documentId, pathStack, currentHeading, currentBody);
                currentBody = new StringBuilder();

                int level = m.group(1).length();
                String headingText = m.group(2).strip();
                while (!levelStack.isEmpty() && levelStack.peek()[0] >= level) {
                    levelStack.pop();
                    pathStack.remove(pathStack.size() - 1);
                }
                levelStack.push(new int[] {level});
                pathStack.add(headingText);
                currentHeading = headingText;
            } else if (currentHeading != null && !line.isBlank()) {
                if (currentBody.length() > 0) {
                    currentBody.append('\n');
                }
                currentBody.append(line.strip());
            }
        }
        flush(result, project, documentId, pathStack, currentHeading, currentBody);

        return result;
    }

    private void flush(List<RuleChunk> result, String project, long documentId,
                       List<String> pathStack, String heading, StringBuilder body) {
        if (heading == null || body.isEmpty()) {
            return;
        }
        String sectionPath = String.join(" > ", pathStack);
        result.add(new RuleChunk(0, documentId, project, sectionPath, heading, body.toString()));
    }
}
