package com.codemind.integration.application;

import com.codemind.common.event.DomainEvent;
import com.codemind.common.event.PullRequestReceived;
import com.codemind.integration.application.port.PullRequestPayload;
import com.codemind.integration.application.port.WebhookPayloadParser;
import com.codemind.integration.domain.event.DomainEventPublisher;
import com.codemind.integration.domain.webhook.WebhookDeliveryStore;
import com.codemind.integration.domain.webhook.WebhookSignatureVerifier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 编排逻辑单测，端口用内存实现（Redis / MQ 的真实链路在 *IT 里验）。
 */
class WebhookApplicationServiceTest {

    private static final String SECRET = "test-secret";
    private static final byte[] BODY = """
            {"action":"opened","repository":{"full_name":"octocat/hello-world"},
             "pull_request":{"number":7,"head":{"sha":"headsha"},"base":{"sha":"basesha"}}}
            """.getBytes(StandardCharsets.UTF_8);

    private final Set<String> processed = new HashSet<>();
    private final List<DomainEvent> published = new ArrayList<>();
    private String action = "opened";

    private WebhookApplicationService service;

    @BeforeEach
    void setUp() {
        WebhookDeliveryStore store = processed::add;
        WebhookPayloadParser parser = raw -> new PullRequestPayload(
                action, "octocat/hello-world", 7, "headsha", "basesha");
        DomainEventPublisher publisher = published::add;
        service = new WebhookApplicationService(
                new WebhookSignatureVerifier(SECRET), store, parser, publisher);
    }

    @Test
    void 合法请求发出事件() {
        WebhookResult result = handle("delivery-1", "pull_request");

        assertThat(result).isEqualTo(WebhookResult.ACCEPTED);
        assertThat(published).hasSize(1);
        PullRequestReceived event = (PullRequestReceived) published.getFirst();
        assertThat(event.repo()).isEqualTo("octocat/hello-world");
        assertThat(event.prNumber()).isEqualTo(7);
        assertThat(event.headSha()).isEqualTo("headsha");
        assertThat(event.deliveryId()).isEqualTo("delivery-1");
        assertThat(event.eventId()).isNotBlank();
    }

    @Test
    void 同一deliveryId重放第二次不再发事件() {
        assertThat(handle("delivery-1", "pull_request")).isEqualTo(WebhookResult.ACCEPTED);
        assertThat(handle("delivery-1", "pull_request")).isEqualTo(WebhookResult.DUPLICATE);

        assertThat(published).hasSize(1);
    }

    @Test
    void 验签失败抛异常且不发事件() {
        assertThatThrownBy(() -> service.handleGithubWebhook(
                "delivery-1", "pull_request", "sha256=deadbeef", BODY))
                .isInstanceOf(InvalidSignatureException.class);

        assertThat(published).isEmpty();
        assertThat(processed).isEmpty();
    }

    @Test
    void 非pull_request事件被忽略() {
        assertThat(handle("delivery-1", "push")).isEqualTo(WebhookResult.IGNORED);
        assertThat(published).isEmpty();
    }

    @Test
    void 非目标动作被忽略且不占用幂等键() {
        action = "closed";
        assertThat(handle("delivery-1", "pull_request")).isEqualTo(WebhookResult.IGNORED);

        assertThat(published).isEmpty();
        // 忽略的动作不应占住 deliveryId
        assertThat(processed).isEmpty();
    }

    @Test
    void synchronize动作也触发评审() {
        action = "synchronize";
        assertThat(handle("delivery-2", "pull_request")).isEqualTo(WebhookResult.ACCEPTED);
        assertThat(published).hasSize(1);
    }

    @Test
    void 缺少deliveryId时报错() {
        assertThatThrownBy(() -> handle(null, "pull_request"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("X-GitHub-Delivery");
    }

    private WebhookResult handle(String deliveryId, String eventType) {
        return service.handleGithubWebhook(deliveryId, eventType, sign(BODY), BODY);
    }

    private static String sign(byte[] body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return "sha256=" + HexFormat.of().formatHex(mac.doFinal(body));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
