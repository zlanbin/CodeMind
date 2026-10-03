package com.codemind.gateway;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * 校验 Spring 上下文能加载。禁用 Nacos，避免测试依赖外部组件。
 */
@SpringBootTest(properties = {
        "spring.cloud.nacos.discovery.enabled=false",
        "spring.cloud.discovery.enabled=false",
        "spring.cloud.gateway.server.webflux.discovery.locator.enabled=false"
})
class GatewayApplicationTest {

    @Test
    void contextLoads() {
    }
}
