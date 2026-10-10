package com.codemind.integration.interfaces.web.dto;

import java.util.List;

/**
 * 批量拉文件内容的请求（M7a internal 端点）。
 *
 * @param repo  仓库全名，形如 owner/name
 * @param ref   commit SHA 或分支名；评审用 PR 的 headSha
 * @param paths 仓库内相对路径列表
 */
public record FileContentsRequest(String repo, String ref, List<String> paths) {
}
