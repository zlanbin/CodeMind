package com.codemind.review.infrastructure.llm;

import com.codemind.review.domain.agent.FindingJsonParser;
import com.codemind.review.domain.agent.FindingParseException;
import com.codemind.review.domain.changemap.ChangeMapJsonParser;
import com.codemind.review.domain.changemap.ChangeMapParseException;
import com.codemind.review.domain.finding.Finding;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link StubLlmClient} 单测（M12 loadtest profile）。
 *
 * <p>验证各类 callSite 返回的样例 JSON 能被对应解析器解析成功，以及确定性延迟落在 [2s, 8s] 区间内。
 */
class StubLlmClientTest {

    @Test
    void returnsLegalFindingJsonForReviewDimensionCallSites() throws FindingParseException {
        StubLlmClient client = new StubLlmClient(new SimpleMeterRegistry());
        for (String callSite : List.of("STYLE", "SECURITY", "PERFORMANCE", "TEST")) {
            String raw = client.complete("sys", "user", callSite);
            List<Finding> findings = new FindingJsonParser(new ObjectMapper()).parse(raw);
            assertThat(findings).isNotNull().isNotEmpty();
        }
    }

    @Test
    void returnsLegalChangeMapJsonForChangeMapCallSite() throws ChangeMapParseException {
        StubLlmClient client = new StubLlmClient(new SimpleMeterRegistry());
        String raw = client.complete("sys", "user", "CHANGE_MAP");

        var changeMap = new ChangeMapJsonParser(new ObjectMapper())
                .parse(raw, List.of("src/main/java/Sample.java"), List.of());

        assertThat(changeMap).isNotNull();
        assertThat(changeMap.intent()).isNotBlank();
    }

    @Test
    void returnsLegalGlobalReduceJsonForGlobalReduceCallSite() throws FindingParseException {
        StubLlmClient client = new StubLlmClient(new SimpleMeterRegistry());
        String raw = client.complete("sys", "user", "GLOBAL_REDUCE");

        List<Finding> findings = new FindingJsonParser(new ObjectMapper()).parse(raw);
        assertThat(findings).isNotNull().isNotEmpty();
    }

    @Test
    void returnsFilteredFalseJsonForFindingFilterCallSite() throws Exception {
        StubLlmClient client = new StubLlmClient(new SimpleMeterRegistry());
        String raw = client.complete("sys", "user", "FINDING_FILTER");

        JsonNode node = new ObjectMapper().readTree(raw);
        assertThat(node.has("filtered")).isTrue();
        assertThat(node.get("filtered").asBoolean()).isFalse();
    }

    @Test
    void sleepsBetweenTwoAndEightSeconds() {
        long start = System.currentTimeMillis();
        new StubLlmClient(new SimpleMeterRegistry()).complete("sys", "user", "STYLE");
        long elapsed = System.currentTimeMillis() - start;
        assertThat(elapsed).isBetween(2000L, 8100L); // 留 100ms 余量给方法本身的开销
    }

    @Test
    void recordsFixedFakeTokenUsageTaggedByCallSite() {
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        StubLlmClient client = new StubLlmClient(meterRegistry);

        client.complete("sys", "user", "STYLE");

        assertThat(meterRegistry.counter("codemind_llm_tokens_total", "call_site", "STYLE", "type", "prompt")
                .count()).isEqualTo(500.0);
        assertThat(meterRegistry.counter("codemind_llm_tokens_total", "call_site", "STYLE", "type", "completion")
                .count()).isEqualTo(200.0);
    }
}
