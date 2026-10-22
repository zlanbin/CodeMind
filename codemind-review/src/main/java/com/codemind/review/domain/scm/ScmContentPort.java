package com.codemind.review.domain.scm;

import java.util.List;
import java.util.Map;

/**
 * 取 PR 内容的出站端口（M7a）。
 *
 * <p>review 不直连 GitHub：拉 diff 与文件内容是 integration 的职责（ARCHITECTURE 3.3），
 * 本端口的实现经 Feign 调 integration 的 internal 端点。
 *
 * <p>纯接口，domain 不依赖 Spring Cloud / Feign。
 */
public interface ScmContentPort {

    /**
     * 整份 unified diff。
     *
     * @throws ScmContentException 取不到（上游失败、网络不通）
     */
    String fetchDiff(String repo, int prNumber);

    /**
     * 批量取文件在 {@code ref} 上的新侧完整源码。AST 按方法边界切片需要它（见 M2 决策日志）。
     *
     * @param paths 要取的路径；空列表表示不需要源码（如 PR 里没有 Java 文件）
     * @return 路径 → 文件全文；<b>只含成功取到的</b>，调用方要容忍缺失（切片器会降级为 hunk 切片）
     * @throws ScmContentException 整批调用失败（单个文件失败由上游跳过，不抛）
     */
    Map<String, String> fetchNewSources(String repo, String ref, List<String> paths);
}
