package com.codemind.review.infrastructure.scm;

import com.codemind.review.domain.scm.CompareResult;
import com.codemind.review.domain.scm.ScmContentException;
import com.codemind.review.domain.scm.ScmContentPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * {@link ScmContentPort} 的 Feign 实现（M7a）：把 domain 端口调用转成对 integration 的 HTTP 调用，
 * 并把 Feign 的异常类型挡在 domain 之外。
 */
@Component
public class FeignScmContentAdapter implements ScmContentPort {

    private static final Logger log = LoggerFactory.getLogger(FeignScmContentAdapter.class);

    private final IntegrationScmFeignClient feignClient;

    public FeignScmContentAdapter(IntegrationScmFeignClient feignClient) {
        this.feignClient = Objects.requireNonNull(feignClient, "feignClient");
    }

    @Override
    public String fetchDiff(String repo, int prNumber) {
        try {
            return feignClient.diff(repo, prNumber);
        } catch (RuntimeException e) {
            throw new ScmContentException(
                    "取 diff 失败 repo=%s pr=%d".formatted(repo, prNumber), e);
        }
    }

    @Override
    public Map<String, String> fetchNewSources(String repo, String ref, List<String> paths) {
        if (paths == null || paths.isEmpty()) {
            return Map.of();
        }
        try {
            FileContentsResponseDto response =
                    feignClient.contents(new FileContentsRequestDto(repo, ref, paths));
            Map<String, String> contents =
                    response == null || response.contents() == null ? Map.of() : response.contents();
            if (contents.size() < paths.size()) {
                log.warn("部分文件源码未取到 repo={} ref={} 请求 {} 个，取到 {} 个（缺失的文件将降级为 hunk 切片）",
                        repo, ref, paths.size(), contents.size());
            }
            return contents;
        } catch (RuntimeException e) {
            throw new ScmContentException(
                    "取文件源码失败 repo=%s ref=%s 共 %d 个文件".formatted(repo, ref, paths.size()), e);
        }
    }

    @Override
    public CompareResult fetchCompareDiff(String repo, String oldHead, String newHead) {
        try {
            CompareDiffResponseDto response = feignClient.compareDiff(repo, oldHead, newHead);
            return new CompareResult(response.status(), response.diffText());
        } catch (RuntimeException e) {
            throw new ScmContentException(
                    "取增量 diff 失败 repo=%s oldHead=%s newHead=%s".formatted(repo, oldHead, newHead), e);
        }
    }
}
