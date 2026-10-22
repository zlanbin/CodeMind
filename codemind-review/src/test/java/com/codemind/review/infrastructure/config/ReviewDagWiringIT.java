package com.codemind.review.infrastructure.config;

import com.codemind.review.application.ReviewExecutionService;
import com.codemind.review.domain.dag.DagOrchestrator;
import com.codemind.review.domain.dag.DagScheduler;
import com.codemind.review.domain.scm.ScmContentPort;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * M7a 装配验证：DAG 引擎与入口链路的 bean 在真实容器里都能装起来。
 *
 * <p>M5 曾因只改 Config 不验装配而首次 {@code mvn verify} 失败（{@code ReviewAgentIT} 注入了
 * 已不存在的 bean），这个 IT 就是为此设的。
 */
@SpringBootTest
@TestPropertySource(properties = {
        "spring.cloud.nacos.discovery.enabled=false",
        "spring.ai.openai.api-key=placeholder-no-key"
})
class ReviewDagWiringIT {

    @Autowired
    private DagOrchestrator dagOrchestrator;

    @Autowired
    private DagScheduler dagScheduler;

    @Autowired
    private ReviewExecutionService reviewExecutionService;

    @Autowired
    private ScmContentPort scmContentPort;

    @Autowired
    private com.fasterxml.jackson.databind.ObjectMapper sharedObjectMapper;

    @Test
    void contextLoadsWithAllDagBeans() {
        assertNotNull(dagOrchestrator, "DagOrchestrator 应装配成 bean（M3~M6 一直只在测试里 new）");
        assertNotNull(dagScheduler);
        assertNotNull(reviewExecutionService);
        assertNotNull(scmContentPort, "Feign 适配器应实现 ScmContentPort");
    }

    /**
     * 容器里的共享 ObjectMapper 必须是 Boot 自动配置的那个，不带类型信息。
     *
     * <p>M7a 踩过：checkpoint 专用 mapper（开了 default typing）曾注册成 bean，Boot 的
     * JacksonAutoConfiguration 随即退让，它变成全局唯一 mapper——Feign 请求体里 {@code paths}
     * 被写成 {@code ["java.util.ArrayList", [...]]}，integration 回 400；Spring AI 请求体同样被污染，
     * 三个真实 LLM IT 全部超时。
     */
    @Test
    void sharedObjectMapperWritesPlainJson() throws Exception {
        String json = sharedObjectMapper.writeValueAsString(
                java.util.Map.of("paths", java.util.List.of("A.java")));
        assertEquals("{\"paths\":[\"A.java\"]}", json,
                "共享 ObjectMapper 不应写类型信息，实际: " + json);
    }
}
