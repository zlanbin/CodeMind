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

    @org.springframework.test.context.bean.override.convention.TestBean(name = "rocketMqDomainEventPublisher", methodName = "publicationTestPublisher")
    private com.codemind.review.domain.event.DomainEventPublisher publicationTestPublisher;

    static com.codemind.review.domain.event.DomainEventPublisher publicationTestPublisher() {
        return new com.codemind.review.support.RecordingDomainEventPublisher();
    }

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

    @Autowired
    private com.codemind.review.domain.finding.FindingFilter findingFilter;

    @Autowired
    private com.codemind.review.domain.publication.ReviewOutboxRepository reviewOutboxRepository;

    @Autowired
    private com.codemind.review.application.ReviewOutboxDispatcher reviewOutboxDispatcher;

    @Autowired
    private com.codemind.review.application.ReviewCompletionService reviewCompletionService;

    @Autowired
    private com.codemind.review.application.ReviewPublicationService reviewPublicationService;

    @Autowired
    private com.codemind.review.interfaces.mq.ReviewPublishedListener reviewPublishedListener;

    @Autowired
    private com.codemind.review.domain.task.LastReviewedHeadRepository lastReviewedHeadRepository;

    @Autowired
    private com.codemind.review.domain.finding.TaskFingerprintPort taskFingerprintPort;

    @Autowired
    private com.codemind.review.application.FingerprintRegistrationDispatcher fingerprintRegistrationDispatcher;

    @Test
    void contextLoadsWithAllDagBeans() {
        assertNotNull(dagOrchestrator, "DagOrchestrator 应装配成 bean（M3~M6 一直只在测试里 new）");
        assertNotNull(dagScheduler);
        assertNotNull(reviewExecutionService);
        assertNotNull(scmContentPort, "Feign 适配器应实现 ScmContentPort");
    }

    /** M9b：Dedup & Filter 的 FindingFilter 及其 MemoryRecallPort 依赖应在真实容器里装配成功。 */
    @Test
    void contextLoadsWithFindingFilterBean() {
        assertNotNull(findingFilter, "FindingFilter 应装配成 bean（FindingFilterConfig）");
    }

    /**
     * M10：增量评审的全量/增量分流、已发布指纹过滤、指纹登记账本 dispatcher 依赖应在真实容器里装配成功。
     * 两个 @FeignClient 都指向 codemind-memory（MemoryFeignClient、TaskFingerprintFeignClient）曾在真实
     * 容器里因同名 FeignClientSpecification 冲突报 BeanDefinitionOverrideException，这条断言正好守住这一点。
     */
    @Test
    void contextLoadsWithM10Beans() {
        assertNotNull(lastReviewedHeadRepository, "LastReviewedHeadRepository 应装配成 bean");
        assertNotNull(taskFingerprintPort, "TaskFingerprintPort 应装配成 bean（FeignTaskFingerprintAdapter）");
        assertNotNull(fingerprintRegistrationDispatcher, "指纹登记 dispatcher 应装配成 bean（FingerprintSchedulingConfig）");
    }

    /**
     * S6 评审 I-1（Task 9）：这个 IT 的注释自述「M5 曾因只改 Config 不验装配而首次 verify 失败，
     * 这个 IT 就是为此设的」——Task 9 正好新增了 MQ bean 和一批 outbox/发布装配，当时只改了
     * @TestBean 的 name，没有扩这条断言，没有守住它本该守的场景。
     */
    @Test
    void contextLoadsWithM7bPublicationBeans() {
        assertNotNull(reviewOutboxRepository, "V5 outbox 仓储应装配成 bean");
        assertNotNull(reviewOutboxDispatcher, "投递器应装配成 bean（PublicationSchedulingConfig）");
        assertNotNull(reviewCompletionService, "完成交接服务应装配成 bean");
        assertNotNull(reviewPublicationService, "发布回执服务应装配成 bean");
        assertNotNull(reviewPublishedListener, "ReviewPublished 消费者应装配成 bean");
        assertNotNull(publicationTestPublisher, "DomainEventPublisher 唯一候选应是测试替身而非真实 MQ 发布器");
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
