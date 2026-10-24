package com.codemind.review.infrastructure.llm;

import com.codemind.review.domain.agent.LlmClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * M12 Task 5：验证默认 profile 下容器里唯一的 {@link LlmClient} bean 具体类型是
 * {@link SpringAiLlmClient}（Review Focus 第 4 条——不能只靠肉眼看 {@code @Profile} 注解断定生效）。
 * loadtest profile 的对应断言见 {@link LlmClientLoadtestProfileWiringIT}。
 *
 * <p>真实容器加载需要 PostgreSQL（Flyway）与 RocketMQ，归为 IT（{@code mvn verify} 前置
 * {@code cd deploy && docker compose up -d}），与 {@link com.codemind.review.ReviewApplicationIT}
 * 同样的基础设施依赖。
 */
@SpringBootTest
@TestPropertySource(properties = {
        "spring.cloud.nacos.discovery.enabled=false",
        "spring.ai.openai.api-key=placeholder-no-key"
})
class LlmClientProfileWiringIT {

    @org.springframework.test.context.bean.override.convention.TestBean(
            name = "rocketMqDomainEventPublisher", methodName = "publicationTestPublisher")
    private com.codemind.review.domain.event.DomainEventPublisher publicationTestPublisher;

    static com.codemind.review.domain.event.DomainEventPublisher publicationTestPublisher() {
        return new com.codemind.review.support.RecordingDomainEventPublisher();
    }

    @Autowired
    private LlmClient llmClient;

    @Test
    void defaultProfileWiresSpringAiLlmClient() {
        assertThat(llmClient).isExactlyInstanceOf(SpringAiLlmClient.class);
    }
}
