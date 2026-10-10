package com.codemind.review.infrastructure.scm;

import java.util.List;

/**
 * 批量取文件内容的请求体（M7a）。
 *
 * <p>review 侧自己定义，不复用 integration 的 DTO：两个服务独立演进，共享 DTO 会让
 * integration 的接口变更直接传染到 review。字段名需与 integration 的
 * {@code FileContentsRequest} 对齐（JSON 按名字匹配）。
 */
public record FileContentsRequestDto(String repo, String ref, List<String> paths) {
}
