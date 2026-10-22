package com.codemind.integration.application;

import com.codemind.integration.application.port.ScmClient;
import com.codemind.integration.application.port.ScmException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 对内提供 PR 内容（M7a）。review 服务经 internal 端点调用，自己不碰 GitHub。
 */
@Service
public class PullRequestContentService {

    private static final Logger log = LoggerFactory.getLogger(PullRequestContentService.class);

    private final ScmClient scmClient;

    public PullRequestContentService(ScmClient scmClient) {
        this.scmClient = Objects.requireNonNull(scmClient, "scmClient");
    }

    /** 整份 unified diff。拉不到就抛 {@link ScmException}——没有 diff 评审无从开始。 */
    public String diff(String repo, int prNumber) {
        log.debug("拉取 diff repo={} pr={}", repo, prNumber);
        return scmClient.fetchPullRequestDiff(repo, prNumber);
    }

    /**
     * 批量拉文件在 {@code ref} 上的内容。
     *
     * <p><b>单个文件失败不拖垮整批</b>：跳过并记 warn，返回的 Map 里就没这个 key。
     * 调用方（切片器）对缺失的源码会降级为 hunk 切片，少一个文件的 AST 精度
     * 远好于整次评审失败。
     *
     * @return 路径 → 文件全文；只含成功拉到的
     */
    public Map<String, String> fileContents(String repo, String ref, List<String> paths) {
        if (paths == null || paths.isEmpty()) {
            return Map.of();
        }
        Map<String, String> contents = new LinkedHashMap<>();
        for (String path : paths) {
            try {
                contents.put(path, scmClient.fetchFileContent(repo, path, ref));
            } catch (ScmException e) {
                log.warn("拉取文件内容失败，跳过该文件 repo={} path={} ref={} status={} 原因={}",
                        repo, path, ref, e.statusCode(), e.getMessage());
            }
        }
        log.debug("拉取文件内容 repo={} ref={} 请求 {} 个，成功 {} 个",
                repo, ref, paths.size(), contents.size());
        return contents;
    }
}
