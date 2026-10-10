package com.codemind.review.domain.changemap;

import com.codemind.review.domain.agent.JsonFences;
import com.codemind.review.domain.chunk.SignatureChange;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;

/**
 * 把 ChangeMap 的 LLM 输出解析为 {@link ChangeMap}（M6）。
 *
 * <p>约定结构（见 {@link ChangeMapAgent} 的系统提示）：
 * <pre>{@code
 * { "intent": "...", "modules": ["..."], "highRiskFiles": ["..."] }
 * }</pre>
 *
 * <p>严格校验 {@code intent} 非空（这是 ChangeMap 的核心产出，空了没有意义）；
 * {@code modules} / {@code highRiskFiles} 缺失按空列表处理。确定性部分
 * （文件列表、签名变更）不由 LLM 产出，由调用方传入。
 */
public final class ChangeMapJsonParser {

    private final ObjectMapper objectMapper;

    public ChangeMapJsonParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public ChangeMapJsonParser() {
        this(new ObjectMapper());
    }

    public ChangeMap parse(String rawOutput, List<String> files, List<SignatureChange> signatureChanges)
            throws ChangeMapParseException {
        if (rawOutput == null || rawOutput.isBlank()) {
            throw new ChangeMapParseException("LLM 输出为空");
        }
        String json = JsonFences.strip(rawOutput).trim();

        JsonNode root;
        try {
            root = objectMapper.readTree(json);
        } catch (Exception e) {
            throw new ChangeMapParseException("ChangeMap 输出不是合法 JSON: " + JsonFences.preview(json), e);
        }
        if (!root.isObject()) {
            throw new ChangeMapParseException("ChangeMap 输出不是 JSON 对象: " + JsonFences.preview(json));
        }

        JsonNode intentNode = root.get("intent");
        if (intentNode == null || intentNode.isNull() || intentNode.asText().isBlank()) {
            throw new ChangeMapParseException("ChangeMap 缺少必填字段 intent: " + JsonFences.preview(json));
        }

        return ChangeMap.of(
                intentNode.asText().trim(),
                readStringArray(root, "modules"),
                readStringArray(root, "highRiskFiles"),
                files,
                signatureChanges);
    }

    private static List<String> readStringArray(JsonNode root, String field) {
        JsonNode node = root.get(field);
        if (node == null || !node.isArray()) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        for (JsonNode item : node) {
            if (item != null && !item.isNull() && !item.asText().isBlank()) {
                values.add(item.asText().trim());
            }
        }
        return values;
    }
}
