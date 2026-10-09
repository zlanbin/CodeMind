package com.codemind.review.domain.agent;

import com.codemind.review.domain.finding.Category;
import com.codemind.review.domain.finding.Finding;
import com.codemind.review.domain.finding.Severity;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 把 LLM 原始文本输出解析为 {@link Finding} 列表。
 *
 * <p>约定的结构化输出（见 {@link ReviewAgent} 的系统提示）：
 * <pre>{@code
 * { "findings": [
 *     { "file": "...", "line": 12, "severity": "MAJOR", "category": "SECURITY",
 *       "message": "...", "suggestion": "...", "codeSnippet": "..." }
 * ] }
 * }</pre>
 *
 * <p>容错：剥除 markdown 代码围栏（```json ... ```）后再解析。
 * 严格校验：非 JSON、缺 {@code findings} 数组、单条缺必填字段或枚举非法，一律抛
 * {@link FindingParseException}，交由 {@link ReviewAgent} 重试/降级。Jackson 为纯计算库，不耦合 Spring。
 */
public final class FindingJsonParser {

    private static final Pattern FENCE =
            Pattern.compile("```(?:json)?\\s*(.*?)\\s*```", Pattern.DOTALL);

    private final ObjectMapper objectMapper;

    public FindingJsonParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public FindingJsonParser() {
        this(new ObjectMapper());
    }

    public List<Finding> parse(String rawOutput) throws FindingParseException {
        if (rawOutput == null || rawOutput.isBlank()) {
            throw new FindingParseException("LLM 输出为空");
        }
        String json = stripFence(rawOutput).trim();

        JsonNode root;
        try {
            root = objectMapper.readTree(json);
        } catch (Exception e) {
            throw new FindingParseException("LLM 输出不是合法 JSON: " + preview(json), e);
        }

        JsonNode findingsNode = root.get("findings");
        if (findingsNode == null || !findingsNode.isArray()) {
            throw new FindingParseException("缺少 findings 数组字段: " + preview(json));
        }

        List<Finding> findings = new ArrayList<>();
        for (JsonNode node : findingsNode) {
            findings.add(parseOne(node));
        }
        return findings;
    }

    private Finding parseOne(JsonNode node) throws FindingParseException {
        if (!node.isObject()) {
            throw new FindingParseException("finding 不是对象: " + node);
        }
        String file = requireText(node, "file");
        String message = requireText(node, "message");
        String severityText = requireText(node, "severity");
        String categoryText = requireText(node, "category");

        int line = node.has("line") && node.get("line").canConvertToInt()
                ? Math.max(0, node.get("line").asInt())
                : 0;
        String suggestion = node.hasNonNull("suggestion") ? node.get("suggestion").asText() : "";
        String codeSnippet = node.hasNonNull("codeSnippet") ? node.get("codeSnippet").asText() : message;

        Severity severity = parseEnum(Severity.class, severityText, "severity");
        Category category = parseEnum(Category.class, categoryText, "category");

        try {
            return Finding.of(file, line, severity, category, message, suggestion, codeSnippet);
        } catch (IllegalArgumentException e) {
            throw new FindingParseException("finding 字段非法: " + e.getMessage(), e);
        }
    }

    private static <E extends Enum<E>> E parseEnum(Class<E> type, String value, String field)
            throws FindingParseException {
        try {
            return Enum.valueOf(type, value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new FindingParseException(
                    "%s 枚举非法: %s（允许 %s）".formatted(field, value, List.of(type.getEnumConstants())));
        }
    }

    private static String requireText(JsonNode node, String field) throws FindingParseException {
        JsonNode v = node.get(field);
        if (v == null || v.isNull() || v.asText().isBlank()) {
            throw new FindingParseException("finding 缺少必填字段 " + field + ": " + node);
        }
        return v.asText();
    }

    private static String stripFence(String text) {
        Matcher m = FENCE.matcher(text);
        if (m.find()) {
            return m.group(1);
        }
        return text;
    }

    private static String preview(String s) {
        String oneLine = s.replaceAll("\\s+", " ").trim();
        return oneLine.length() > 200 ? oneLine.substring(0, 200) + "…" : oneLine;
    }
}
