package com.codemind.integration.application;

import com.codemind.integration.application.port.ScmClient;
import com.codemind.integration.application.port.ScmException;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link PullRequestContentService} 纯单测。重点是「单个文件失败不拖垮整批」——
 * 一个 PR 里有文件拉不到（权限、二进制、临时抖动）不该让整次评审失败。
 */
class PullRequestContentServiceTest {

    @Test
    void fetchesEachRequestedPathAtGivenRef() {
        RecordingScmClient scm = new RecordingScmClient();
        PullRequestContentService service = new PullRequestContentService(scm);

        Map<String, String> contents =
                service.fileContents("owner/repo", "sha1", List.of("A.java", "B.java", "C.java"));

        assertEquals(3, contents.size());
        assertEquals("source of A.java", contents.get("A.java"));
        assertEquals(List.of("A.java", "B.java", "C.java"), scm.requestedPaths);
        assertEquals(List.of("sha1", "sha1", "sha1"), scm.requestedRefs);
    }

    @Test
    void singleFileFailureDoesNotFailWholeBatch() {
        RecordingScmClient scm = new RecordingScmClient();
        scm.failingPath = "B.java";
        PullRequestContentService service = new PullRequestContentService(scm);

        Map<String, String> contents =
                service.fileContents("owner/repo", "sha1", List.of("A.java", "B.java", "C.java"));

        assertEquals(2, contents.size());
        assertTrue(contents.containsKey("A.java"));
        assertTrue(contents.containsKey("C.java"));
        assertFalse(contents.containsKey("B.java"), "拉失败的文件不应出现在结果里");
    }

    @Test
    void emptyPathListSkipsScmEntirely() {
        RecordingScmClient scm = new RecordingScmClient();
        PullRequestContentService service = new PullRequestContentService(scm);

        Map<String, String> contents = service.fileContents("owner/repo", "sha1", List.of());

        assertTrue(contents.isEmpty());
        assertEquals(List.of(), scm.requestedPaths, "空列表不应产生任何 GitHub 调用");
    }

    /** 记录调用参数的桩，并可指定某个 path 抛 404。 */
    private static final class RecordingScmClient implements ScmClient {

        private final List<String> requestedPaths = new ArrayList<>();
        private final List<String> requestedRefs = new ArrayList<>();
        private String failingPath;

        @Override
        public String fetchPullRequestDiff(String repo, int prNumber) {
            return "diff --git a/A.java b/A.java\n";
        }

        @Override
        public String fetchFileContent(String repo, String path, String ref) {
            requestedPaths.add(path);
            requestedRefs.add(ref);
            if (path.equals(failingPath)) {
                throw new ScmException("模拟 404: " + path, 404);
            }
            return "source of " + path;
        }
    }
}
