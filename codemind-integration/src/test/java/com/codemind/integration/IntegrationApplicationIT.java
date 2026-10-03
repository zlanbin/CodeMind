package com.codemind.integration;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * 上下文加载。M1 起需要真实 Redis 与 RocketMQ（RocketMQTemplate 启动即连 namesrv），
 * 因此归为 IT：前置条件 {@code cd deploy && docker compose up -d}。
 */
@SpringBootTest(properties = {
        "spring.cloud.nacos.discovery.enabled=false",
        "spring.cloud.discovery.enabled=false",
        "codemind.webhook.github-secret=it-context-secret"
})
class IntegrationApplicationIT {

    @Test
    void contextLoads() {
    }
}
