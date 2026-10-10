package com.codemind.review.infrastructure.config;

import com.codemind.review.application.ReviewExecutionService;
import com.codemind.review.domain.dag.DagOrchestrator;
import com.codemind.review.domain.dag.DagScheduler;
import com.codemind.review.domain.scm.ScmContentPort;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

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

    @Test
    void contextLoadsWithAllDagBeans() {
        assertNotNull(dagOrchestrator, "DagOrchestrator 应装配成 bean（M3~M6 一直只在测试里 new）");
        assertNotNull(dagScheduler);
        assertNotNull(reviewExecutionService);
        assertNotNull(scmContentPort, "Feign 适配器应实现 ScmContentPort");
    }
}
