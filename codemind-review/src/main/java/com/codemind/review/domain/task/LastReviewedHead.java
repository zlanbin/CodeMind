package com.codemind.review.domain.task;

import java.time.Instant;

/** 某个 PR 上一次成功发布的评审基准（M10 增量评审）。 */
public record LastReviewedHead(String repo, int prNumber, String baseSha, String headSha, Instant updatedAt) {
}
