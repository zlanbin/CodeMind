package com.codemind.review.infrastructure.memory;

import com.codemind.review.domain.memory.RecalledMemory;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * S6 评审发现：{@code MemoryFeignClient.recall} 曾声明裸 {@code RecallResponseDto} 返回类型，
 * 而 memory 的 internal 端点统一套 {@code Result} 信封，导致 Feign 解码后 {@code memories} 恒为
 * {@code null}，{@code FindingFilter} 在生产环境形同空操作。本 IT 对<b>真实运行中的 memory 服务</b>
 * 发起写入 + 召回，证明契约真的对得上——纯 stub 的 {@code FeignMemoryRecallAdapterTest} 挡不住这类
 * 「DTO 结构假设与真实 wire format 不一致」的缺陷。
 *
 * <p>前置（与 {@code RealPullRequestReviewIT} 同风格，齐备才跑，否则 {@code assumeTrue} 跳过）：
 * <pre>
 * cd deploy &amp;&amp; docker compose up -d
 * # memory 必须在跑（本 IT 关了 discovery，Feign 直连 127.0.0.1:8083）
 * java -jar codemind-memory/target/codemind-memory-0.1.0-SNAPSHOT.jar &amp;
 * </pre>
 */
@SpringBootTest
@TestPropertySource(properties = {
        "spring.cloud.nacos.discovery.enabled=false",
        // 关了 discovery，Feign 直连本机 memory（须先启动它，见类注释前置条件）
        "codemind.memory.url=${CODEMIND_MEMORY_URL:http://127.0.0.1:8083}"
})
class MemoryFeignClientIT {

    @org.springframework.test.context.bean.override.convention.TestBean(name = "rocketMqDomainEventPublisher", methodName = "publicationTestPublisher")
    private com.codemind.review.domain.event.DomainEventPublisher publicationTestPublisher;

    static com.codemind.review.domain.event.DomainEventPublisher publicationTestPublisher() {
        return new com.codemind.review.support.RecordingDomainEventPublisher();
    }

    @Autowired
    private MemoryFeignClient feignClient;

    @Autowired
    private FeignMemoryRecallAdapter adapter;

    @Test
    void recallUnwrapsRealMemoryResponseEnvelope() throws Exception {
        assumeTrue(memoryIsReachable(), "memory 未在 127.0.0.1:8083 运行，跳过真实召回 IT");

        String project = "codemind-review-it/" + UUID.randomUUID();
        String content = "本项目不要求对内部来源的 sku 参数进行 null/empty 校验";
        writeMemory(project, content);

        List<RecalledMemory> recalled = adapter.recallRejectedPatterns(project, content, 5, 0.0);

        assertEquals(1, recalled.size(), "真实召回应命中刚写入的否决记忆，而不是因契约不匹配返回空列表");
        assertEquals(content, recalled.get(0).content());
        assertTrue(recalled.get(0).score() > 0, "真实打分应为正数");
    }

    private static boolean memoryIsReachable() {
        try {
            HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
            HttpResponse<Void> response = client.send(
                    HttpRequest.newBuilder(URI.create(memoryUrl() + "/actuator/health"))
                            .timeout(Duration.ofSeconds(2)).GET().build(),
                    HttpResponse.BodyHandlers.discarding());
            return response.statusCode() == 200;
        } catch (Exception e) {
            return false;
        }
    }

    private static void writeMemory(String project, String content) throws Exception {
        String body = """
                {"project":"%s","kind":"REJECTED_PATTERN","content":"%s","importance":0.9}
                """.formatted(project, content);
        HttpClient client = HttpClient.newHttpClient();
        HttpResponse<String> response = client.send(
                HttpRequest.newBuilder(URI.create(memoryUrl() + "/internal/memories"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertTrue(response.statusCode() == 200 || response.statusCode() == 201,
                "写入记忆失败: " + response.statusCode() + " " + response.body());
    }

    private static String memoryUrl() {
        String override = System.getenv("CODEMIND_MEMORY_URL");
        return override != null && !override.isBlank() ? override : "http://127.0.0.1:8083";
    }
}
