package com.codemind.integration.interfaces.web.dto;

/** {@code GET /internal/pulls/compare-diff} 响应体（M10）。 */
public record CompareDiffResponse(String status, String diffText) {
}
