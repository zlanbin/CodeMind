package com.codemind.review.domain.finding;

import com.codemind.review.domain.agent.JsonFences;
import com.codemind.review.domain.agent.LlmClient;
import com.codemind.review.domain.agent.LlmException;
import com.codemind.review.domain.memory.MemoryRecallException;
import com.codemind.review.domain.memory.MemoryRecallPort;
import com.codemind.review.domain.memory.RecalledMemory;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Dedup &amp; Filter（M9b，ARCHITECTURE 7.2 第 5 步）：对每条 Finding 召回同项目的否决记忆候选，
 * 再由 LLM 判定是否属于被否决的同类意见（D3 方案 A，{@code docs/plans/M9.md}）。
 *
 * <p>召回失败、无候选、或 LLM 判定失败（重试耗尽、输出畸形）一律<b>放行</b>——局部失败不该压制
 * 真缺陷，只记一条降级说明交调用方并入报告的 degradations（与 M4 {@code ReviewAgent} 的降级思路一致）。
 */
public final class FindingFilter {

    private static final Logger log = LoggerFactory.getLogger(FindingFilter.class);

    // Global Constraints：minScore 沿用 M9a 冲突消解的 CONFLICT_MIN_SCORE
    private static final int TOP_K = 5;
    private static final double MIN_SCORE = 0.05;

    private static final String SYSTEM_PROMPT = """
            你是代码评审系统的记忆过滤器。开发者此前否决过一些评审意见，形成了「否决规则」。
            现在有一条新的评审意见，以及若干条可能相关的否决规则。
            判断这条新意见是否属于某条否决规则描述的同类问题——即如果再给开发者看，
            会不会被同样否决。只有在新意见确实是否决规则覆盖的同一类问题时才判定为 filtered=true。
            不同维度、不同语义的问题即使表面措辞相似也不算同类，必须判 filtered=false。
            否决规则不会无中生有地扩大到它没提到的问题上。
            只输出 JSON：{"filtered": true|false}，不要任何解释。""";

    private final MemoryRecallPort recallPort;
    private final LlmClient llmClient;
    private final int maxRetries;
    private final ObjectMapper objectMapper;

    public FindingFilter(MemoryRecallPort recallPort, LlmClient llmClient, int maxRetries) {
        this.recallPort = Objects.requireNonNull(recallPort, "recallPort");
        this.llmClient = Objects.requireNonNull(llmClient, "llmClient");
        if (maxRetries < 0) {
            throw new IllegalArgumentException("maxRetries 不能为负");
        }
        this.maxRetries = maxRetries;
        this.objectMapper = new ObjectMapper();
    }

    public FilterOutcome filter(String project, List<Finding> findings) {
        List<Finding> kept = new ArrayList<>();
        List<String> llmDegradations = new ArrayList<>();
        String recallFailureReason = null;
        int recallFailureCount = 0;
        for (Finding finding : findings) {
            List<RecalledMemory> candidates;
            try {
                candidates = recallPort.recallRejectedPatterns(project,
                        finding.category() + ": " + finding.message(), TOP_K, MIN_SCORE);
            } catch (MemoryRecallException e) {
                recallFailureReason = e.getMessage();
                recallFailureCount++;
                kept.add(finding);
                continue;
            }
            if (candidates.isEmpty() || !shouldFilter(finding, candidates, llmDegradations)) {
                kept.add(finding);
            }
        }
        List<String> degradations = new ArrayList<>(llmDegradations);
        if (recallFailureCount > 0) {
            // 同一根因（如 memory 服务不可达）在每条 Finding 上重复出现，合并成一条，
            // 不然会在公开 PR 评论里刷出近乎重复的内部错误信息（S6 评审发现）
            degradations.add("记忆召回失败，%d 条意见全部放行：%s".formatted(recallFailureCount, recallFailureReason));
        }
        return new FilterOutcome(kept, degradations);
    }

    /** @return {@code true} 表示该 Finding 判定为被否决的同类意见，应从报告中过滤掉 */
    private boolean shouldFilter(Finding finding, List<RecalledMemory> candidates, List<String> degradations) {
        Boolean filtered = judge(finding, candidates, degradations);
        boolean result = Boolean.TRUE.equals(filtered);
        if (result) {
            log.info("Finding 判定为同类否决意见，已过滤 fingerprint={} category={} 命中候选={}",
                    finding.fingerprint(), finding.category(),
                    candidates.stream().map(RecalledMemory::content).toList());
        }
        return result;
    }

    /** @return {@code true}/{@code false} 为 LLM 判定结果；{@code null} 表示判定失败，按放行处理 */
    private Boolean judge(Finding finding, List<RecalledMemory> candidates, List<String> degradations) {
        String user = userPrompt(finding, candidates);
        String lastError = "";
        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            try {
                // "FINDING_FILTER" 是 M12 可观测性指标 call_site 标签的正式值（第 4 个调用方，
                // 补充 brief/plan 原列的 STYLE/SECURITY/PERFORMANCE/TEST/CHANGE_MAP/GLOBAL_REDUCE
                // 6 个）。已由协调者裁决确认，详见
                // docs/superpowers/plans/2026-10-24-m12-observability-loadtest.md 的 Ruling 记录。
                String raw = llmClient.complete(SYSTEM_PROMPT, user, "FINDING_FILTER");
                return parse(raw);
            } catch (LlmException | IllegalArgumentException e) {
                lastError = e.getMessage();
            }
        }
        degradations.add("LLM 判定失败，放行 fingerprint=" + finding.fingerprint() + "：" + lastError);
        return null;
    }

    private Boolean parse(String raw) {
        String json = JsonFences.strip(raw).trim();
        JsonNode root;
        try {
            root = objectMapper.readTree(json);
        } catch (Exception e) {
            throw new IllegalArgumentException("LLM 输出不是合法 JSON: " + JsonFences.preview(json), e);
        }
        JsonNode filtered = root.get("filtered");
        if (filtered == null || !filtered.isBoolean()) {
            throw new IllegalArgumentException("缺少 filtered 字段: " + JsonFences.preview(json));
        }
        return filtered.booleanValue();
    }

    private static String userPrompt(Finding finding, List<RecalledMemory> candidates) {
        String rules = candidates.stream()
                .map(c -> "- " + c.content())
                .collect(Collectors.joining("\n"));
        return """
                否决规则候选：
                %s
                新的评审意见：
                维度：%s
                描述：%s""".formatted(rules, finding.category(), finding.message());
    }
}
