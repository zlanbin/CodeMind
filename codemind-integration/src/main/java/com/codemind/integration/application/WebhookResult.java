package com.codemind.integration.application;

/**
 * Webhook 处理结果。三种都回 200，用于区分链路行为。
 */
public enum WebhookResult {

    /** 验签与幂等通过，已发出 PullRequestReceived。 */
    ACCEPTED,

    /** 同一 deliveryId 此前已处理，本次不再发事件。 */
    DUPLICATE,

    /** 非 pull_request 事件，或动作不在 opened / synchronize 内，直接忽略。 */
    IGNORED
}
