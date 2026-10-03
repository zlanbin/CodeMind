package com.codemind.integration.interfaces.web;

import com.codemind.common.api.Result;
import com.codemind.integration.application.WebhookApplicationService;
import com.codemind.integration.application.WebhookResult;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * GitHub webhook 入口。
 *
 * <p>请求体用 {@code byte[]} 接收而非反序列化成对象：验签必须对原始字节做 HMAC，
 * 反序列化再序列化会改变字节内容。
 */
@RestController
@RequestMapping("/webhook")
public class GithubWebhookController {

    private final WebhookApplicationService webhookApplicationService;

    public GithubWebhookController(WebhookApplicationService webhookApplicationService) {
        this.webhookApplicationService = webhookApplicationService;
    }

    @PostMapping(path = "/github", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Result<String> receive(
            @RequestHeader(name = "X-GitHub-Delivery", required = false) String deliveryId,
            @RequestHeader(name = "X-GitHub-Event", required = false) String eventType,
            @RequestHeader(name = "X-Hub-Signature-256", required = false) String signature,
            @RequestBody byte[] rawBody) {
        WebhookResult result = webhookApplicationService
                .handleGithubWebhook(deliveryId, eventType, signature, rawBody);
        return Result.ok(result.name());
    }
}
