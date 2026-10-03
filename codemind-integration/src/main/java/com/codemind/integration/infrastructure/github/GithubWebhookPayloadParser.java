package com.codemind.integration.infrastructure.github;

import com.codemind.integration.application.port.PullRequestPayload;
import com.codemind.integration.application.port.WebhookPayloadParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * 解析 GitHub pull_request webhook 请求体。
 * 字段路径见 https://docs.github.com/webhooks/webhook-events-and-payloads#pull_request
 */
@Component
public class GithubWebhookPayloadParser implements WebhookPayloadParser {

    private final ObjectMapper objectMapper;

    public GithubWebhookPayloadParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public PullRequestPayload parsePullRequest(byte[] rawBody) {
        JsonNode root;
        try {
            root = objectMapper.readTree(rawBody);
        } catch (IOException e) {
            throw new IllegalArgumentException("webhook 请求体不是合法 JSON", e);
        }

        JsonNode pr = root.path("pull_request");
        String action = text(root, "action", "action");
        String repo = text(root.path("repository"), "full_name", "repository.full_name");
        int prNumber = requireInt(pr, "number");
        String headSha = text(pr.path("head"), "sha", "pull_request.head.sha");
        String baseSha = text(pr.path("base"), "sha", "pull_request.base.sha");

        return new PullRequestPayload(action, repo, prNumber, headSha, baseSha);
    }

    private String text(JsonNode node, String field, String path) {
        JsonNode value = node.path(field);
        if (!value.isTextual() || value.asText().isBlank()) {
            throw new IllegalArgumentException("webhook 请求体缺少字段 " + path);
        }
        return value.asText();
    }

    private int requireInt(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (!value.isInt()) {
            throw new IllegalArgumentException("webhook 请求体缺少字段 pull_request." + field);
        }
        return value.asInt();
    }
}
