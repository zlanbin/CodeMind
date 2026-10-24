package com.codemind.review.domain.agent;

import com.codemind.review.domain.chunk.ReviewChunk;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link ReviewAgent} 纯单测：用可控 stub {@link LlmClient} 确定性验证
 * ROADMAP M4 的「非法输出触发重试/降级」。真实 LLM 输出不可控，无法在此确定性复现畸形输出，
 * 故重试/降级路径用 stub 验证，真实调用见 {@code ReviewAgentIT}。
 */
class ReviewAgentTest {

    private static final ReviewChunk CHUNK = new ReviewChunk(
            "chunk-1", List.of("OrderService.java"), List.of("OrderService#cancel"),
            "@@ -1,3 +1,4 @@\n+int x = 1;", 10, false);

    private static final String VALID = """
            {"findings":[{"file":"OrderService.java","line":1,"severity":"MINOR",
             "category":"STYLE","message":"命名","suggestion":"改名","codeSnippet":"int x"}]}
            """;

    /** 按序返回预设响应的 stub；记录实际调用次数。 */
    private static final class StubLlmClient implements LlmClient {
        private final Deque<Object> responses = new ArrayDeque<>();
        int calls = 0;

        StubLlmClient(Object... scripted) {
            for (Object r : scripted) {
                responses.add(r);
            }
        }

        @Override
        public String complete(String systemPrompt, String userPrompt, String callSite) throws LlmException {
            calls++;
            Object next = responses.isEmpty() ? "" : responses.poll();
            if (next instanceof LlmException e) {
                throw e;
            }
            return (String) next;
        }
    }

    @Test
    void validOutputFirstTry() {
        StubLlmClient stub = new StubLlmClient(VALID);
        ReviewAgent agent = new ReviewAgent(ReviewDimension.STYLE, stub, new FindingJsonParser(), 2);

        AgentReview review = agent.review(CHUNK);

        assertFalse(review.degraded());
        assertEquals(1, review.findings().size());
        assertEquals(1, review.attempts());
        assertEquals(1, stub.calls, "合法输出应一次成功，不重试");
    }

    @Test
    void illegalThenValidTriggersRetrySuccess() {
        // 第 1 次畸形 → 重试 → 第 2 次合法
        StubLlmClient stub = new StubLlmClient("这不是 JSON", VALID);
        ReviewAgent agent = new ReviewAgent(ReviewDimension.SECURITY, stub, new FindingJsonParser(), 2);

        AgentReview review = agent.review(CHUNK);

        assertFalse(review.degraded());
        assertEquals(1, review.findings().size());
        assertEquals(2, review.attempts(), "首次失败后应重试一次成功");
        assertEquals(2, stub.calls);
    }

    @Test
    void persistentIllegalOutputDegrades() {
        // 始终畸形：初次 + 2 次重试全失败 → 降级
        StubLlmClient stub = new StubLlmClient("x", "y", "z", VALID /* 不该被取到 */);
        ReviewAgent agent = new ReviewAgent(ReviewDimension.PERFORMANCE, stub, new FindingJsonParser(), 2);

        AgentReview review = agent.review(CHUNK);

        assertTrue(review.degraded(), "重试耗尽应降级");
        assertTrue(review.findings().isEmpty());
        assertEquals(3, review.attempts(), "初次 + 2 次重试 = 3 次");
        assertEquals(3, stub.calls);
        assertNotNull(review.degradeReason());
        assertTrue(review.degradeReason().contains("不合法"));
    }

    @Test
    void llmCallFailureAlsoRetriesThenDegrades() {
        StubLlmClient stub = new StubLlmClient(
                new LlmException("503"), new LlmException("超时"), new LlmException("限流"));
        ReviewAgent agent = new ReviewAgent(ReviewDimension.TEST, stub, new FindingJsonParser(), 2);

        AgentReview review = agent.review(CHUNK);

        assertTrue(review.degraded());
        assertEquals(3, review.attempts());
        assertTrue(review.degradeReason().contains("LLM 调用失败"));
    }

    @Test
    void zeroRetriesDegradesImmediately() {
        StubLlmClient stub = new StubLlmClient("bad");
        ReviewAgent agent = new ReviewAgent(ReviewDimension.STYLE, stub, new FindingJsonParser(), 0);

        AgentReview review = agent.review(CHUNK);

        assertTrue(review.degraded());
        assertEquals(1, review.attempts(), "maxRetries=0 时只尝试一次");
        assertEquals(1, stub.calls);
    }

    /** 捕获实际发给 LlmClient 的 userPrompt，供 M11 规则注入断言用。 */
    private static final class CapturingLlmClient implements LlmClient {
        String lastUserPrompt;

        @Override
        public String complete(String systemPrompt, String userPrompt, String callSite) throws LlmException {
            lastUserPrompt = userPrompt;
            return VALID;
        }
    }

    @Test
    void nonEmptyRulesAreInjectedIntoPrompt() {
        CapturingLlmClient capturing = new CapturingLlmClient();
        ReviewAgent agent = new ReviewAgent(ReviewDimension.STYLE, capturing, new FindingJsonParser(), 2);
        var rules = List.of(new com.codemind.review.domain.rule.RecalledRule(
                "接口设计规范", "3.2.1 方法命名", "方法命名", "公开方法命名必须用名词加动词结构", 0.9));

        agent.review(CHUNK, null, rules);

        assertTrue(capturing.lastUserPrompt.contains("3.2.1 方法命名"));
        assertTrue(capturing.lastUserPrompt.contains("接口设计规范"));
    }

    @Test
    void emptyRulesDoNotAddRuleBlockToPrompt() {
        CapturingLlmClient capturing = new CapturingLlmClient();
        ReviewAgent agent = new ReviewAgent(ReviewDimension.STYLE, capturing, new FindingJsonParser(), 2);

        agent.review(CHUNK, null, List.of());

        assertFalse(capturing.lastUserPrompt.contains("团队规范条款"));
    }

    @Test
    void existingNoArgOverloadStillWorksUnchanged() {
        StubLlmClient stub = new StubLlmClient(VALID);
        ReviewAgent agent = new ReviewAgent(ReviewDimension.STYLE, stub, new FindingJsonParser(), 2);

        assertFalse(agent.review(CHUNK).degraded());
    }

    @Test
    void existingChangeMapOverloadStillWorksUnchanged() {
        StubLlmClient stub = new StubLlmClient(VALID);
        ReviewAgent agent = new ReviewAgent(ReviewDimension.STYLE, stub, new FindingJsonParser(), 2);

        assertFalse(agent.review(CHUNK, null).degraded());
    }
}
