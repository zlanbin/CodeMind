package com.codemind.review.infrastructure.scm;

import java.util.Map;

/**
 * 批量取文件内容的响应体（M7a）。字段名需与 integration 的 {@code FileContentsResponse} 对齐。
 *
 * @param contents 路径 → 文件全文；只含上游成功取到的
 */
public record FileContentsResponseDto(Map<String, String> contents) {
}
