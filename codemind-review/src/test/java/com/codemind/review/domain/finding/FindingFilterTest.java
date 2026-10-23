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
        LlmClient llm = (sys, user) -> {
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
        LlmClient llm = (sys, user) -> {
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
        LlmClient llm = (sys, user) -> "{\"filtered\": true}";
        var filter = new FindingFilter(recall, llm, 1);

        FilterOutcome outcome = filter.filter("org/repo", List.of(NPE_FINDING));

        assertTrue(outcome.kept().isEmpty());
    }

    @Test
    void keepsWhenLlmJudgesDifferentIssue() {
        MemoryRecallPort recall = (project, query, topK, minScore) ->
                List.of(new RecalledMemory("本项目允许在单线程访问场景下使用普通 HashMap", 0.6));
        LlmClient llm = (sys, user) -> "{\"filtered\": false}";
        var filter = new FindingFilter(recall, llm, 1);

        FilterOutcome outcome = filter.filter("org/repo", List.of(NPE_FINDING));

        assertEquals(List.of(NPE_FINDING), outcome.kept());
    }

    @Test
    void passesThroughWhenLlmOutputMalformedAfterRetries() {
        MemoryRecallPort recall = (project, query, topK, minScore) ->
                List.of(new RecalledMemory("某条记忆", 0.5));
        LlmClient llm = (sys, user) -> "不是 JSON";
        var filter = new FindingFilter(recall, llm, 0);

        FilterOutcome outcome = filter.filter("org/repo", List.of(NPE_FINDING));

        assertEquals(List.of(NPE_FINDING), outcome.kept());
        assertEquals(1, outcome.degradations().size());
    }
}
