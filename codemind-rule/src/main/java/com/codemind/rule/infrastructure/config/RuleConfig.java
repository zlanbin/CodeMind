package com.codemind.rule.infrastructure.config;

import com.codemind.rule.application.RuleIngestionService;
import com.codemind.rule.application.RuleRecallService;
import com.codemind.rule.domain.rule.Embedder;
import com.codemind.rule.domain.rule.RuleChunkRepository;
import com.codemind.rule.domain.rule.RuleDocumentRepository;
import com.codemind.rule.domain.rule.RuleRanker;
import com.codemind.rule.infrastructure.embedding.HashingEmbedder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/** domain / infrastructure 的纯类不带 Spring 注解，在这里装配（同 memory 的 MemoryConfig 做法）。 */
@Configuration
public class RuleConfig {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    Embedder embedder() {
        return new HashingEmbedder();
    }

    @Bean
    RuleRanker ruleRanker() {
        return new RuleRanker();
    }

    @Bean
    RuleIngestionService ruleIngestionService(RuleDocumentRepository documentRepository,
                                              RuleChunkRepository chunkRepository, Embedder embedder, Clock clock) {
        return new RuleIngestionService(documentRepository, chunkRepository, embedder, clock);
    }

    @Bean
    RuleRecallService ruleRecallService(RuleChunkRepository chunkRepository, Embedder embedder, RuleRanker ranker) {
        return new RuleRecallService(chunkRepository, embedder, ranker);
    }
}
