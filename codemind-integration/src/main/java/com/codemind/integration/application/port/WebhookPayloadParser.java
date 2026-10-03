package com.codemind.integration.application.port;

/**
 * Webhook 请求体解析端口，实现在 infrastructure 层（按平台区分 JSON 结构）。
 */
public interface WebhookPayloadParser {

    /**
     * @param rawBody 原始请求体字节（验签通过后才调用）
     * @return 解析出的 PR 信息
     * @throws IllegalArgumentException 请求体不是合法 JSON 或缺少必要字段
     */
    PullRequestPayload parsePullRequest(byte[] rawBody);
}
