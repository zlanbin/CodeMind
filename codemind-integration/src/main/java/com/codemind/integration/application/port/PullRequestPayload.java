package com.codemind.integration.application.port;

/**
 * 从 webhook 请求体里解析出来的 PR 信息，屏蔽各平台 JSON 结构差异。
 *
 * @param action   PR 动作，如 opened / synchronize / closed
 * @param repo     仓库全名，形如 owner/name
 * @param prNumber PR 序号
 * @param headSha  源分支 HEAD commit SHA
 * @param baseSha  目标分支基线 commit SHA
 */
public record PullRequestPayload(
        String action,
        String repo,
        int prNumber,
        String headSha,
        String baseSha
) {
}
