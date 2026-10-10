package com.codemind.review.domain.agent;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * LLM 输出的 JSON 预处理：剥 markdown 代码围栏、截断预览（M6 抽出，供
 * {@link FindingJsonParser}、ChunkSummary 解析器、ChangeMap 解析器共用）。
 */
public final class JsonFences {

    private static final Pattern FENCE =
            Pattern.compile("```(?:json)?\\s*(.*?)\\s*```", Pattern.DOTALL);

    private JsonFences() {
    }

    /** 剥掉 ```json ... ``` 围栏；无围栏时原样返回。 */
    public static String strip(String text) {
        if (text == null) {
            return "";
        }
        Matcher m = FENCE.matcher(text);
        return m.find() ? m.group(1) : text;
    }

    /** 压成一行并截断，用于错误信息。 */
    public static String preview(String s) {
        String oneLine = s == null ? "" : s.replaceAll("\\s+", " ").trim();
        return oneLine.length() > 200 ? oneLine.substring(0, 200) + "…" : oneLine;
    }
}
