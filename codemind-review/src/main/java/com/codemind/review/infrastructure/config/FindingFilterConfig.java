package com.codemind.review.infrastructure.config;

import com.codemind.review.domain.agent.LlmClient;
import com.codemind.review.domain.finding.FindingFilter;
import com.codemind.review.domain.memory.MemoryRecallPort;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 把 domain 层的 {@link FindingFilter} 装配成 Spring Bean（M9b）。
 *
 * <p>{@link MemoryRecallPort} 由 {@code FeignMemoryRecallAdapter}（{@code @Component}）自动满足，
 * {@link LlmClient} 已由 {@code ReviewAgentConfig} 装配，两者都无需在此重新定义。
 */
@Configuration
public class FindingFilterConfig {

    @Bean
    public FindingFilter findingFilter(MemoryRecallPort recallPort, LlmClient llmClient,
                                       @Value("${codemind.llm.max-retries:2}") int maxRetries) {
        return new FindingFilter(recallPort, llmClient, maxRetries);
    }
}
