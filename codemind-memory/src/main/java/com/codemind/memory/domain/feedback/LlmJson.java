package com.codemind.memory.domain.feedback;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** LLM 输出的 JSON 读取：剥 markdown 围栏后严格解析（同 review 的 JsonFences 做法）。 */
final class LlmJson {

    private static final Pattern FENCE = Pattern.compile("```(?:json)?\\s*(.*?)\\s*```", Pattern.DOTALL);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private LlmJson() {
    }

    /** @throws IllegalArgumentException 空输出、非 JSON 对象 */
    static JsonNode readObject(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("LLM 输出为空");
        }
        Matcher m = FENCE.matcher(raw);
        String json = (m.find() ? m.group(1) : raw).strip();
        try {
            JsonNode node = MAPPER.readTree(json);
            if (node == null || !node.isObject()) {
                throw new IllegalArgumentException("LLM 输出不是 JSON 对象");
            }
            return node;
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalArgumentException("LLM 输出不是合法 JSON：" + preview(raw));
        }
    }

    static String preview(String s) {
        String oneLine = s == null ? "" : s.replaceAll("\\s+", " ").strip();
        return oneLine.length() > 200 ? oneLine.substring(0, 200) + "…" : oneLine;
    }
}
