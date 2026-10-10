package com.codemind.review.domain.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;

/**
 * 从 LLM 输出里解析 {@link ChunkSummary}（M6，ARCHITECTURE 6.4）。
 *
 * <p>子会话一次调用同时产出 Findings 和摘要，约定结构：
 * <pre>{@code
 * { "findings": [...],
 *   "summary": { "changedSymbols": ["A#foo(int)"], "intent": "...",
 *                "risks": ["..."], "crossChunkQuestions": ["..."] } }
 * }</pre>
 *
 * <p>严格校验：缺 {@code summary} 对象、或 {@code changedSymbols} / {@code intent} 为空，
 * 一律抛 {@link ChunkSummaryParseException}。{@link ReviewAgent} 据此把摘要降级为空，
 * 但<b>不影响</b>同一次输出里已解析成功的 Findings——摘要降级不该让评审意见一起丢。
 */
public final class ChunkSummaryJsonParser {

    private final ObjectMapper objectMapper;

    public ChunkSummaryJsonParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public ChunkSummaryJsonParser() {
        this(new ObjectMapper());
    }

    public ChunkSummary parse(String rawOutput, String chunkId) throws ChunkSummaryParseException {
        if (rawOutput == null || rawOutput.isBlank()) {
            throw new ChunkSummaryParseException("LLM 输出为空");
        }
        String json = JsonFences.strip(rawOutput).trim();

        JsonNode root;
        try {
            root = objectMapper.readTree(json);
        } catch (Exception e) {
            throw new ChunkSummaryParseException("输出不是合法 JSON: " + JsonFences.preview(json), e);
        }

        JsonNode summaryNode = root.get("summary");
        if (summaryNode == null || !summaryNode.isObject()) {
            throw new ChunkSummaryParseException("缺少 summary 对象: " + JsonFences.preview(json));
        }

        List<String> changedSymbols = readStringArray(summaryNode, "changedSymbols");
        if (changedSymbols.isEmpty()) {
            throw new ChunkSummaryParseException("summary.changedSymbols 为空: " + JsonFences.preview(json));
        }
        JsonNode intentNode = summaryNode.get("intent");
        if (intentNode == null || intentNode.isNull() || intentNode.asText().isBlank()) {
            throw new ChunkSummaryParseException("summary.intent 为空: " + JsonFences.preview(json));
        }

        return ChunkSummary.of(
                chunkId,
                changedSymbols,
                intentNode.asText().trim(),
                readStringArray(summaryNode, "risks"),
                readStringArray(summaryNode, "crossChunkQuestions"));
    }

    private static List<String> readStringArray(JsonNode parent, String field) {
        JsonNode node = parent.get(field);
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
