package com.codemind.review.domain.finding;

import com.codemind.review.domain.memory.MemoryRecallException;
import com.codemind.review.domain.memory.MemoryRecallPort;
import com.codemind.review.domain.memory.RecalledMemory;
import com.codemind.review.domain.agent.LlmClient;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FindingFilterTest {

    private static final Finding NPE_FINDING = Finding.of("Order.java", 10, Severity.BLOCKER,
            Category.CORRECTNESS, "可能空指针", "加判空", "stock.get(sku)");

    @Test
    void passesThroughWhenNoCandidatesRecalled() {
        MemoryRecallPort recall = (project, query, topK, minScore) -> List.of();
        LlmClient llm = (sys, user, callSite) -> {
            throw new AssertionError("不该调用 LLM：无候选");
        };
        var filter = new FindingFilter(recall, llm, 1);

        FilterOutcome outcome = filter.filter("org/repo", List.of(NPE_FINDING));

        assertEquals(List.of(NPE_FINDING), outcome.kept());
        assertTrue(outcome.degradations().isEmpty());
    }

    @Test
    void passesThroughWhenRecallFails() {
        MemoryRecallPort recall = (project, query, topK, minScore) -> {
            throw new MemoryRecallException("memory 不可达", null);
        };
        LlmClient llm = (sys, user, callSite) -> {
            throw new AssertionError("不该调用 LLM：召回已失败");
        };
        var filter = new FindingFilter(recall, llm, 1);

        FilterOutcome outcome = filter.filter("org/repo", List.of(NPE_FINDING));

        assertEquals(List.of(NPE_FINDING), outcome.kept());
        assertEquals(1, outcome.degradations().size());
    }

    @Test
    void filtersOutWhenLlmJudgesSameCategory() {
        MemoryRecallPort recall = (project, query, topK, minScore) ->
                List.of(new RecalledMemory("本项目不要求对内部来源的 sku 做 null/empty 校验", 0.8));
        LlmClient llm = (sys, user, callSite) -> "{\"filtered\": true}";
        var filter = new FindingFilter(recall, llm, 1);

        FilterOutcome outcome = filter.filter("org/repo", List.of(NPE_FINDING));

        assertTrue(outcome.kept().isEmpty());
    }

    @Test
    void keepsWhenLlmJudgesDifferentIssue() {
        MemoryRecallPort recall = (project, query, topK, minScore) ->
                List.of(new RecalledMemory("本项目允许在单线程访问场景下使用普通 HashMap", 0.6));
        LlmClient llm = (sys, user, callSite) -> "{\"filtered\": false}";
        var filter = new FindingFilter(recall, llm, 1);

        FilterOutcome outcome = filter.filter("org/repo", List.of(NPE_FINDING));

        assertEquals(List.of(NPE_FINDING), outcome.kept());
    }

    @Test
    void passesThroughWhenLlmOutputMalformedAfterRetries() {
        MemoryRecallPort recall = (project, query, topK, minScore) ->
                List.of(new RecalledMemory("某条记忆", 0.5));
        LlmClient llm = (sys, user, callSite) -> "不是 JSON";
        var filter = new FindingFilter(recall, llm, 0);

        FilterOutcome outcome = filter.filter("org/repo", List.of(NPE_FINDING));

        assertEquals(List.of(NPE_FINDING), outcome.kept());
        assertEquals(1, outcome.degradations().size());
    }

    @Test
    void llmFailureDegradationIncludesLastError() {
        MemoryRecallPort recall = (project, query, topK, minScore) ->
                List.of(new RecalledMemory("某条记忆", 0.5));
        LlmClient llm = (sys, user, callSite) -> {
            throw new com.codemind.review.domain.agent.LlmException("中转端点 429 限流");
        };
        var filter = new FindingFilter(recall, llm, 0);

        FilterOutcome outcome = filter.filter("org/repo", List.of(NPE_FINDING));

        assertTrue(outcome.degradations().get(0).contains("429 限流"),
                "降级说明要带上失败原因，不能只说「LLM 判定失败」：" + outcome.degradations().get(0));
    }

    @Test
    void recallFailureAcrossMultipleFindingsCollapsesIntoOneDegradation() {
        Finding other = Finding.of("Other.java", 5, Severity.MAJOR,
                Category.STYLE, "命名不规范", "改名", "int x");
        MemoryRecallPort recall = (project, query, topK, minScore) -> {
            throw new MemoryRecallException("memory 不可达", null);
        };
        LlmClient llm = (sys, user, callSite) -> {
            throw new AssertionError("召回已失败，不该调用 LLM");
        };
        var filter = new FindingFilter(recall, llm, 1);

        FilterOutcome outcome = filter.filter("org/repo", List.of(NPE_FINDING, other));

        assertEquals(List.of(NPE_FINDING, other), outcome.kept(), "召回失败要放行全部 Finding");
        assertEquals(1, outcome.degradations().size(),
                "同一根因的召回失败应合并成一条，不该每条 Finding 各占一行刷屏公开评论：" + outcome.degradations());
    }

    @Test
    void onlyFindingMatchingRecalledRuleIsFiltered() {
        Finding perfIssue = Finding.of("Cache.java", 20, Severity.MINOR,
                Category.PERFORMANCE, "单线程场景使用了普通 HashMap", "无需改动", "new HashMap<>()");
        MemoryRecallPort recall = (project, query, topK, minScore) -> query.startsWith("PERFORMANCE")
                ? List.of(new RecalledMemory("本项目允许在单线程访问场景下使用普通 HashMap", 0.6))
                : List.of(new RecalledMemory("本项目不要求对内部来源的 sku 做 null/empty 校验", 0.8));
        LlmClient llm = (sys, user, callSite) -> user.contains("单线程访问场景下使用普通 HashMap")
                ? "{\"filtered\": true}"
                : "{\"filtered\": false}";
        var filter = new FindingFilter(recall, llm, 0);

        FilterOutcome outcome = filter.filter("org/repo", List.of(NPE_FINDING, perfIssue));

        assertEquals(List.of(NPE_FINDING), outcome.kept(),
                "只有命中召回规则的 PERFORMANCE 意见该被过滤，CORRECTNESS 的 NPE 不该受影响");
    }
}
