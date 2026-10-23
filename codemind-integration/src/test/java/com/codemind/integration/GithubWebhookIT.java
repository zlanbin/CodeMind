package com.codemind.integration;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Webhook 端到端：真实 Redis 幂等 + 真实 RocketMQ 发送。
 *
 * <p>前置条件：{@code cd deploy && docker compose up -d}。
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.cloud.nacos.discovery.enabled=false",
                "spring.cloud.discovery.enabled=false",
                "codemind.webhook.github-secret=" + GithubWebhookIT.SECRET,
                // 本类专用 topic 与 producer group，不污染其它 IT 或业务链路
                "codemind.mq.topic.pull-request-received=codemind-pull-request-received-webhook-it",
                "rocketmq.producer.group=codemind-integration-webhook-it"
        })
class GithubWebhookIT {

    static final String SECRET = "it-webhook-secret";

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private StringRedisTemplate redisTemplate;

    /** 每轮用随机 deliveryId，避免上次运行留下的幂等键影响结果。 */
    private final String deliveryId = "it-" + UUID.randomUUID();

    @AfterEach
    void cleanUp() {
        redisTemplate.delete("webhook:" + deliveryId);
    }

    @Test
    void 签名错误返回401() {
        ResponseEntity<String> response = post(deliveryId, "pull_request", "sha256=deadbeef", body());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        // 验签失败不该占用幂等键
        assertThat(redisTemplate.hasKey("webhook:" + deliveryId)).isFalse();
    }

    @Test
    void 缺少签名头返回401() {
        assertThat(post(deliveryId, "pull_request", null, body()).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void 重放同一deliveryId第二次返回DUPLICATE() {
        String payload = body();

        ResponseEntity<String> first = post(deliveryId, "pull_request", sign(payload), payload);
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(first.getBody()).contains("ACCEPTED");

        ResponseEntity<String> second = post(deliveryId, "pull_request", sign(payload), payload);
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(second.getBody()).contains("DUPLICATE");

        // Redis 幂等键确实写入了
        assertThat(redisTemplate.hasKey("webhook:" + deliveryId)).isTrue();
    }

    @Test
    void 非目标动作返回IGNORED() {
        String payload = body("closed");
        ResponseEntity<String> response = post(deliveryId, "pull_request", sign(payload), payload);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("IGNORED");
    }

    @Test
    void 非pull_request事件返回IGNORED() {
        String payload = body();
        ResponseEntity<String> response = post(deliveryId, "push", sign(payload), payload);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("IGNORED");
    }

    @Test
    void 请求体缺字段返回400() {
        String payload = "{\"action\":\"opened\"}";
        ResponseEntity<String> response = post(deliveryId, "pull_request", sign(payload), payload);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    private ResponseEntity<String> post(String delivery, String eventType,
                                        String signature, String payload) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (delivery != null) {
            headers.set("X-GitHub-Delivery", delivery);
        }
        headers.set("X-GitHub-Event", eventType);
        if (signature != null) {
            headers.set("X-Hub-Signature-256", signature);
        }
        return restTemplate.exchange("/webhook/github", HttpMethod.POST,
                new HttpEntity<>(payload, headers), String.class);
    }

    private static String body() {
        return body("opened");
    }

    private static String body(String action) {
        return """
                {"action":"%s","repository":{"full_name":"codemind/it-repo"},
                 "pull_request":{"number":1,"head":{"sha":"headsha"},"base":{"sha":"basesha"}}}
                """.formatted(action);
    }

    private static String sign(String payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] digest = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            return "sha256=" + HexFormat.of().formatHex(digest);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
