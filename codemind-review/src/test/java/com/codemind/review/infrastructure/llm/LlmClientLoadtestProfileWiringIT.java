package com.codemind.review.infrastructure.llm;

import com.codemind.review.domain.agent.LlmClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * M12 Task 5：{@code loadtest} profile 下验证容器里唯一的 {@link LlmClient} bean 具体类型是
 * {@link StubLlmClient}。默认 profile 的对应断言见 {@link LlmClientProfileWiringIT}——两个测试
 * 分别在各自 profile 下加载独立的 {@code ApplicationContext}，互不干扰，共同证明
 * {@code @Profile("!loadtest")} / {@code @Profile("loadtest")} 的互斥按预期生效。
 *
 * <p>真实容器加载需要 PostgreSQL（Flyway）与 RocketMQ，归为 IT（{@code mvn verify} 前置
 * {@code cd deploy && docker compose up -d}）。
 */
@SpringBootTest
@ActiveProfiles("loadtest")
@TestPropertySource(properties = {
        "spring.cloud.nacos.discovery.enabled=false",
        "spring.ai.openai.api-key=placeholder-no-key"
})
class LlmClientLoadtestProfileWiringIT {

    @org.springframework.test.context.bean.override.convention.TestBean(
            name = "rocketMqDomainEventPublisher", methodName = "publicationTestPublisher")
    private com.codemind.review.domain.event.DomainEventPublisher publicationTestPublisher;

    static com.codemind.review.domain.event.DomainEventPublisher publicationTestPublisher() {
        return new com.codemind.review.support.RecordingDomainEventPublisher();
    }

    @Autowired
    private LlmClient llmClient;

    @Test
    void loadtestProfileWiresStubLlmClient() {
        assertThat(llmClient).isExactlyInstanceOf(StubLlmClient.class);
    }
}
