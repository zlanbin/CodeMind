package com.codemind.review.domain.scm;

/**
 * 对比两次 commit 的结果（M10）。
 *
 * @param status   GitHub 返回的对比状态原文（{@code ahead}/{@code behind}/{@code diverged}/{@code identical}）
 * @param diffText 增量 diff 文本；{@code status} 非 {@code ahead} 时为空串
 */
public record CompareResult(String status, String diffText) {
    public CompareResult {
        diffText = diffText == null ? "" : diffText;
    }

    public boolean isAhead() {
        return "ahead".equals(status);
    }
}
