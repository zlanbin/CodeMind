package com.codemind.review.infrastructure.scm;

/** integration 的 {@code GET /internal/pulls/compare-diff} 响应体（M10，自行定义，不跨模块共享）。 */
public record CompareDiffResponseDto(String status, String diffText) {
}
