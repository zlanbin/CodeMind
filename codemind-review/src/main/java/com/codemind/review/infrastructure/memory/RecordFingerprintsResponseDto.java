package com.codemind.review.infrastructure.memory;

/** 调 memory 的 {@code POST /internal/task-fingerprints} 响应体（M10，自行定义，不跨模块共享）。 */
public record RecordFingerprintsResponseDto(int added) {
}
