package com.codemind.integration.application;

import com.codemind.common.event.PullRequestReceived;
import com.codemind.integration.application.port.PullRequestPayload;
import com.codemind.integration.application.port.WebhookPayloadParser;
import com.codemind.integration.domain.event.DomainEventPublisher;
import com.codemind.integration.domain.webhook.WebhookDeliveryStore;
import com.codemind.integration.domain.webhook.WebhookSignatureVerifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Set;

/**
 * Webhook 用例编排：验签 → 过滤事件与动作 → 幂等 → 发 PullRequestReceived。
 */
@Service
public class WebhookApplicationService {

    private static final Logger log = LoggerFactory.getLogger(WebhookApplicationService.class);

    /** 只有这两个动作需要评审：新开 PR 和 PR 分支有新 push。 */
    private static final Set<String> REVIEWABLE_ACTIONS = Set.of("opened", "synchronize");
    private static final String PULL_REQUEST_EVENT = "pull_request";

    private final WebhookSignatureVerifier signatureVerifier;
    private final WebhookDeliveryStore deliveryStore;
    private final WebhookPayloadParser payloadParser;
    private final DomainEventPublisher eventPublisher;

    public WebhookApplicationService(WebhookSignatureVerifier signatureVerifier,
                                     WebhookDeliveryStore deliveryStore,
                                     WebhookPayloadParser payloadParser,
                                     DomainEventPublisher eventPublisher) {
        this.signatureVerifier = signatureVerifier;
        this.deliveryStore = deliveryStore;
        this.payloadParser = payloadParser;
        this.eventPublisher = eventPublisher;
    }

    /**
     * @param deliveryId      X-GitHub-Delivery
     * @param eventType       X-GitHub-Event
     * @param signatureHeader X-Hub-Signature-256
     * @param rawBody         原始请求体字节，验签必须用它
     * @throws InvalidSignatureException 验签失败
     */
    public WebhookResult handleGithubWebhook(String deliveryId, String eventType,
                                             String signatureHeader, byte[] rawBody) {
        if (!signatureVerifier.verify(signatureHeader, rawBody)) {
            // 不记录签名值本身，避免日志泄漏。
            log.warn("webhook 验签失败 deliveryId={} eventType={}", deliveryId, eventType);
            throw new InvalidSignatureException("X-Hub-Signature-256 校验失败");
        }
        if (deliveryId == null || deliveryId.isBlank()) {
            throw new IllegalArgumentException("缺少 X-GitHub-Delivery");
        }

        if (!PULL_REQUEST_EVENT.equals(eventType)) {
            log.debug("忽略非 pull_request 事件 deliveryId={} eventType={}", deliveryId, eventType);
            return WebhookResult.IGNORED;
        }

        PullRequestPayload payload = payloadParser.parsePullRequest(rawBody);
        if (!REVIEWABLE_ACTIONS.contains(payload.action())) {
            log.debug("忽略不需评审的动作 deliveryId={} action={}", deliveryId, payload.action());
            return WebhookResult.IGNORED;
        }

        // 幂等放在解析之后：解析失败属于坏请求，不该把 deliveryId 占住。
        if (!deliveryStore.markProcessed(deliveryId)) {
            log.info("webhook 重放，跳过 deliveryId={} repo={} pr={}",
                    deliveryId, payload.repo(), payload.prNumber());
            return WebhookResult.DUPLICATE;
        }

        PullRequestReceived event = PullRequestReceived.of(deliveryId, payload.action(),
                payload.repo(), payload.prNumber(), payload.headSha(), payload.baseSha());
        eventPublisher.publish(event);
        log.info("已发出 PullRequestReceived eventId={} repo={} pr={} headSha={}",
                event.eventId(), event.repo(), event.prNumber(), event.headSha());
        return WebhookResult.ACCEPTED;
    }
}
