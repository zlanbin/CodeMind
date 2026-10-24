package com.codemind.review.infrastructure.memory;

import java.util.List;

/** 调 memory 的 {@code POST /internal/task-fingerprints} 请求体（M10，自行定义，不跨模块共享）。 */
public record RecordFingerprintsRequestDto(String repo, Integer prNumber, List<String> fingerprints) {
}
