package com.codemind.integration.application.port;

/**
 * GitHub compare 两次 commit 的结果（M10）。
 *
 * @param status   GitHub 响应里的 {@code status} 字段原文（{@code ahead}/{@code behind}/{@code diverged}/{@code identical}）
 * @param diffText 增量 diff 文本；{@code status} 非 {@code ahead} 时为空串（不省一次网络调用取 diff）
 */
public record CompareResult(String status, String diffText) {
    public CompareResult {
        diffText = diffText == null ? "" : diffText;
    }

    public boolean isAhead() {
        return "ahead".equals(status);
    }
}
