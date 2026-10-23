package com.codemind.review;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * 上下文加载。M1 起需要真实 PostgreSQL（Flyway 迁移）与 RocketMQ，
 * 因此归为 IT：前置条件 {@code cd deploy && docker compose up -d}。
 */
@SpringBootTest(properties = {
        "spring.cloud.nacos.discovery.enabled=false",
        "spring.cloud.discovery.enabled=false"
})
class ReviewApplicationIT {

    @org.springframework.test.context.bean.override.convention.TestBean(name = "rocketMqDomainEventPublisher", methodName = "publicationTestPublisher")
    private com.codemind.review.domain.event.DomainEventPublisher publicationTestPublisher;

    static com.codemind.review.domain.event.DomainEventPublisher publicationTestPublisher() {
        return new com.codemind.review.support.RecordingDomainEventPublisher();
    }

    @Test
    void contextLoads() {
    }
}
