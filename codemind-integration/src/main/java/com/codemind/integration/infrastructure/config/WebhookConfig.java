package com.codemind.integration.infrastructure.config;

import com.codemind.integration.domain.webhook.WebhookSignatureVerifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 把 domain 层的纯 Java 对象装配成 Bean，domain 本身不带 Spring 注解。
 */
@Configuration
public class WebhookConfig {

    /**
     * @param secret GitHub webhook secret，来自环境变量 GITHUB_WEBHOOK_SECRET，不落仓库
     */
    @Bean
    public WebhookSignatureVerifier webhookSignatureVerifier(
            @Value("${codemind.webhook.github-secret}") String secret) {
        return new WebhookSignatureVerifier(secret);
    }
}
