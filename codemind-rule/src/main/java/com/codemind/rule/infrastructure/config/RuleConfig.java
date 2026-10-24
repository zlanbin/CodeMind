package com.codemind.rule.infrastructure.config;

import com.codemind.rule.domain.rule.Embedder;
import com.codemind.rule.domain.rule.RuleRanker;
import com.codemind.rule.infrastructure.embedding.HashingEmbedder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** domain / infrastructure 的纯类不带 Spring 注解，在这里装配（同 memory 的 MemoryConfig 做法）。 */
@Configuration
public class RuleConfig {

    @Bean
    Embedder embedder() {
        return new HashingEmbedder();
    }

    @Bean
    RuleRanker ruleRanker() {
        return new RuleRanker();
    }
}
