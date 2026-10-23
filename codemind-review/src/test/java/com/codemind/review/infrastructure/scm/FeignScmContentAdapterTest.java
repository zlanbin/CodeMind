package com.codemind.review.infrastructure.scm;

import com.codemind.review.domain.scm.ScmContentException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link FeignScmContentAdapter} 纯单测：验证 Feign 响应到 domain 端口返回值的映射，
 * 以及 Feign 异常不泄漏到 domain（统一包成 {@link ScmContentException}）。
 */
class FeignScmContentAdapterTest {

    @Test
    void mapsFeignResponseToPathToSourceMap() {
        FeignScmContentAdapter adapter = new FeignScmContentAdapter(new StubFeignClient(
                new FileContentsResponseDto(Map.of("A.java", "class A {}")), null, null));

        Map<String, String> sources =
                adapter.fetchNewSources("owner/repo", "sha1", List.of("A.java"));

        assertEquals(Map.of("A.java", "class A {}"), sources);
    }

    @Test
    void feignFailureBecomesScmContentException() {
        RuntimeException feignFailure = new RuntimeException("connection refused");
        FeignScmContentAdapter adapter =
                new FeignScmContentAdapter(new StubFeignClient(null, null, feignFailure));

        ScmContentException ex = assertThrows(ScmContentException.class,
                () -> adapter.fetchDiff("owner/repo", 7));

        assertSame(feignFailure, ex.getCause(), "原异常应保留为 cause");
    }

    @Test
    void mapsCompareResponseToDomainCompareResult() {
        FeignScmContentAdapter adapter = new FeignScmContentAdapter(new StubFeignClient(
                null, new CompareDiffResponseDto("ahead", "diff text"), null));

        var result = adapter.fetchCompareDiff("org/repo", "old-sha", "new-sha");

        assertEquals("ahead", result.status());
        assertEquals("diff text", result.diffText());
    }

    @Test
    void wrapsCompareFailureAsScmContentException() {
        RuntimeException feignFailure = new RuntimeException("connection refused");
        FeignScmContentAdapter adapter = new FeignScmContentAdapter(new StubFeignClient(null, null, feignFailure));

        ScmContentException ex = assertThrows(ScmContentException.class,
                () -> adapter.fetchCompareDiff("org/repo", "old-sha", "new-sha"));

        assertSame(feignFailure, ex.getCause(), "原异常应保留为 cause");
    }

    /** 按构造参数决定返回响应还是抛异常。 */
    private record StubFeignClient(FileContentsResponseDto response, CompareDiffResponseDto compareResponse,
                                   RuntimeException failure)
            implements IntegrationScmFeignClient {

        @Override
        public String diff(String repo, int prNumber) {
            if (failure != null) {
                throw failure;
            }
            return "diff --git a/A.java b/A.java\n";
        }

        @Override
        public FileContentsResponseDto contents(FileContentsRequestDto request) {
            if (failure != null) {
                throw failure;
            }
            return response;
        }

        @Override
        public CompareDiffResponseDto compareDiff(String repo, String oldHead, String newHead) {
            if (failure != null) {
                throw failure;
            }
            return compareResponse;
        }
    }
}
