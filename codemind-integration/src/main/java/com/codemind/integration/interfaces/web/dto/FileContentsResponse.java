package com.codemind.integration.interfaces.web.dto;

import java.util.Map;

/**
 * 批量拉文件内容的响应（M7a internal 端点）。
 *
 * @param contents 路径 → 文件全文；只含成功拉到的，失败的文件不在 key 里
 */
public record FileContentsResponse(Map<String, String> contents) {
}
