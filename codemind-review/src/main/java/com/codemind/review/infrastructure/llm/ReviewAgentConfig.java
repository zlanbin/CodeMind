package com.codemind.review.infrastructure.llm;

import com.codemind.review.domain.agent.ChunkSummaryJsonParser;
import com.codemind.review.domain.agent.FindingJsonParser;
import com.codemind.review.domain.agent.GlobalReduceAgent;
import com.codemind.review.domain.agent.LlmClient;
import com.codemind.review.domain.agent.ReviewAgent;
import com.codemind.review.domain.agent.ReviewDimension;
import com.codemind.review.domain.changemap.ChangeMapAgent;
import com.codemind.review.domain.changemap.ChangeMapJsonParser;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Map;

/**
 * 把 domain 层的 {@link ReviewAgent} / {@link FindingJsonParser} 装配成 Spring Bean。
 *
 * <p>domain 服务不带 Spring 注解（保持纯度），在此集中装配并注入 {@link LlmClient}。
 * M5 起为 4 个维度各装配一个 Agent。
 */
@Configuration
public class ReviewAgentConfig {

    @Bean
    public FindingJsonParser findingJsonParser(ObjectMapper objectMapper) {
        return new FindingJsonParser(objectMapper);
    }

    @Bean
    public ChunkSummaryJsonParser chunkSummaryJsonParser(ObjectMapper objectMapper) {
        return new ChunkSummaryJsonParser(objectMapper);
    }

    @Bean
    public ChangeMapJsonParser changeMapJsonParser(ObjectMapper objectMapper) {
        return new ChangeMapJsonParser(objectMapper);
    }

    @Bean
    public Map<ReviewDimension, ReviewAgent> reviewAgents(LlmClient llmClient,
                                                          FindingJsonParser parser,
                                                          ChunkSummaryJsonParser summaryParser,
                                                          @Value("${codemind.llm.max-retries:2}") int maxRetries) {
        return Map.of(
                ReviewDimension.STYLE,
                new ReviewAgent(ReviewDimension.STYLE, llmClient, parser, summaryParser, maxRetries),
                ReviewDimension.SECURITY,
                new ReviewAgent(ReviewDimension.SECURITY, llmClient, parser, summaryParser, maxRetries),
                ReviewDimension.PERFORMANCE,
                new ReviewAgent(ReviewDimension.PERFORMANCE, llmClient, parser, summaryParser, maxRetries),
                ReviewDimension.TEST,
                new ReviewAgent(ReviewDimension.TEST, llmClient, parser, summaryParser, maxRetries)
        );
    }

    /** M6：全局变更地图 Agent（轻量会话，不投喂 diff 本体）。 */
    @Bean
    public ChangeMapAgent changeMapAgent(LlmClient llmClient, ChangeMapJsonParser parser,
                                         @Value("${codemind.llm.max-retries:2}") int maxRetries) {
        return new ChangeMapAgent(llmClient, parser, maxRetries);
    }

    /** M6：全局归约 Agent（只读 ChangeMap + 各片摘要）。 */
    @Bean
    public GlobalReduceAgent globalReduceAgent(LlmClient llmClient, FindingJsonParser parser,
                                               @Value("${codemind.llm.max-retries:2}") int maxRetries) {
        return new GlobalReduceAgent(llmClient, parser, maxRetries);
    }
}
