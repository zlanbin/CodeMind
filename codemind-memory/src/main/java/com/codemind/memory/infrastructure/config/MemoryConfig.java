package com.codemind.memory.infrastructure.config;

import com.codemind.memory.application.MemorySettings;
import com.codemind.memory.domain.feedback.ConflictResolver;
import com.codemind.memory.domain.feedback.FeedbackExtractor;
import com.codemind.memory.domain.feedback.LlmClient;
import com.codemind.memory.domain.memory.Embedder;
import com.codemind.memory.domain.recall.MemoryRanker;
import com.codemind.memory.domain.recall.ScoringPolicy;
import com.codemind.memory.infrastructure.embedding.HashingEmbedder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/** domain 服务不带 Spring 注解，在这里装配（同 review 的 ReviewAgentConfig 做法）。 */
@Configuration
@EnableConfigurationProperties(MemoryProperties.class)
public class MemoryConfig {

    /** 测试用 {@code @Primary} 的可控时钟覆盖。 */
    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    Embedder embedder() {
        return new HashingEmbedder();
    }

    @Bean
    ScoringPolicy scoringPolicy(MemoryProperties properties) {
        return new ScoringPolicy(properties.halfLife());
    }

    @Bean
    MemoryRanker memoryRanker(ScoringPolicy policy) {
        return new MemoryRanker(policy);
    }

    @Bean
    FeedbackExtractor feedbackExtractor(LlmClient llmClient, @Value("${codemind.llm.max-retries:2}") int maxRetries) {
        return new FeedbackExtractor(llmClient, maxRetries);
    }

    @Bean
    ConflictResolver conflictResolver(LlmClient llmClient) {
        return new ConflictResolver(llmClient);
    }

    @Bean
    MemorySettings memorySettings(MemoryProperties properties) {
        return new MemorySettings(properties.archiveThreshold(), properties.candidateLimit(),
                properties.defaultTopK());
    }
}
