package com.codemind.review.infrastructure.llm;

import com.codemind.review.domain.agent.FindingJsonParser;
import com.codemind.review.domain.agent.LlmClient;
import com.codemind.review.domain.agent.ReviewAgent;
import com.codemind.review.domain.agent.ReviewDimension;
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
    public Map<ReviewDimension, ReviewAgent> reviewAgents(LlmClient llmClient,
                                                          FindingJsonParser parser,
                                                          @Value("${codemind.llm.max-retries:2}") int maxRetries) {
        return Map.of(
                ReviewDimension.STYLE, new ReviewAgent(ReviewDimension.STYLE, llmClient, parser, maxRetries),
                ReviewDimension.SECURITY, new ReviewAgent(ReviewDimension.SECURITY, llmClient, parser, maxRetries),
                ReviewDimension.PERFORMANCE, new ReviewAgent(ReviewDimension.PERFORMANCE, llmClient, parser, maxRetries),
                ReviewDimension.TEST, new ReviewAgent(ReviewDimension.TEST, llmClient, parser, maxRetries)
        );
    }
}
