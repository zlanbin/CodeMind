package com.codemind.review.infrastructure.memory;

import java.util.List;

/** memory 的 {@code GET /internal/task-fingerprints} 响应体（M10，自行定义，不跨模块共享）。 */
public record PublishedFingerprintsResponseDto(List<String> fingerprints) {
}
