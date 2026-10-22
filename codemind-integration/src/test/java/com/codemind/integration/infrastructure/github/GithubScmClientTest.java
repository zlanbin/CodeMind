package com.codemind.integration.infrastructure.github;

import com.codemind.integration.application.port.ScmException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

/**
 * {@link GithubScmClient} 纯单测：用 {@link MockRestServiceServer} 拦住 HTTP，
 * 断言请求头 / 路径 / 查询参数，以及错误状态到 {@link ScmException} 的映射。
 *
 * <p>不需要中间件，走 surefire。
 */
class GithubScmClientTest {

    private static final String BASE_URL = "https://api.github.test";
    private static final String TOKEN = "ghp-super-secret-token";

    private MockRestServiceServer server;

    /** 绑 mock server 到 builder 后再建客户端，两者共用同一个 RequestFactory。 */
    private GithubScmClient clientWithToken(String token) {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        return new GithubScmClient(builder, BASE_URL, token);
    }

    @Test
    void fetchDiffSendsDiffAcceptHeaderAndBearerToken() {
        GithubScmClient client = clientWithToken(TOKEN);
        String diff = "diff --git a/A.java b/A.java\n@@ -1,1 +1,1 @@\n-old\n+new\n";
        server.expect(requestTo(BASE_URL + "/repos/owner/repo/pulls/7"))
                .andExpect(method(org.springframework.http.HttpMethod.GET))
                .andExpect(header("Accept", "application/vnd.github.v3.diff"))
                .andExpect(header("Authorization", "Bearer " + TOKEN))
                .andExpect(header("X-GitHub-Api-Version", "2022-11-28"))
                .andRespond(withSuccess(diff, MediaType.TEXT_PLAIN));

        String actual = client.fetchPullRequestDiff("owner/repo", 7);

        assertEquals(diff, actual);
        server.verify();
    }

    @Test
    void fetchFileContentSendsRawAcceptHeaderAndRefQuery() {
        GithubScmClient client = clientWithToken(TOKEN);
        String source = "class A {}\n";
        server.expect(requestTo(BASE_URL + "/repos/owner/repo/contents/src/A.java?ref=abc123"))
                .andExpect(method(org.springframework.http.HttpMethod.GET))
                .andExpect(header("Accept", "application/vnd.github.raw"))
                .andRespond(withSuccess(source, MediaType.TEXT_PLAIN));

        String actual = client.fetchFileContent("owner/repo", "src/A.java", "abc123");

        assertEquals(source, actual);
        server.verify();
    }

    /**
     * 路径与 ref 按 URI 规则编码：Git 允许文件名带空格、{@code #}、{@code ?}，
     * 原样拼进 URI 会被截成 fragment / query，拉到错的文件或 404（S6 评审发现）。
     * 目录分隔的 {@code /} 保留，否则 GitHub 认不出层级；query 里的 {@code /} 本就合法，不编。
     */
    @Test
    void pathAndRefAreEncodedButSlashesKept() {
        GithubScmClient client = clientWithToken(TOKEN);
        server.expect(requestTo(BASE_URL + "/repos/owner/repo/contents/src/My%20Dir/A%23B.java?ref=feat/x%23y"))
                .andRespond(withSuccess("class A {}\n", MediaType.TEXT_PLAIN));

        String actual = client.fetchFileContent("owner/repo", "src/My Dir/A#B.java", "feat/x#y");

        assertEquals("class A {}\n", actual);
        server.verify();
    }

    @Test
    void notFoundBecomesScmExceptionWith404() {
        GithubScmClient client = clientWithToken(TOKEN);
        server.expect(requestTo(BASE_URL + "/repos/owner/repo/contents/missing.java?ref=abc123"))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));

        ScmException ex = assertThrows(ScmException.class,
                () -> client.fetchFileContent("owner/repo", "missing.java", "abc123"));

        assertEquals(404, ex.statusCode());
        assertTrue(ex.getMessage().contains("owner/repo"), "message 应含仓库名: " + ex.getMessage());
        assertTrue(ex.getMessage().contains("missing.java"), "message 应含路径: " + ex.getMessage());
    }

    @Test
    void oversizedDiffBecomesScmExceptionWith406() {
        GithubScmClient client = clientWithToken(TOKEN);
        server.expect(requestTo(BASE_URL + "/repos/owner/repo/pulls/9"))
                .andRespond(withStatus(HttpStatus.NOT_ACCEPTABLE));

        ScmException ex = assertThrows(ScmException.class,
                () -> client.fetchPullRequestDiff("owner/repo", 9));

        assertEquals(406, ex.statusCode());
        assertTrue(ex.getMessage().contains("过大"),
                "406 的 message 应说明 diff 过大: " + ex.getMessage());
    }

    @Test
    void tokenNeverAppearsInExceptionMessage() {
        GithubScmClient client = clientWithToken(TOKEN);
        server.expect(requestTo(BASE_URL + "/repos/owner/repo/pulls/1"))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED));

        ScmException ex = assertThrows(ScmException.class,
                () -> client.fetchPullRequestDiff("owner/repo", 1));

        assertEquals(401, ex.statusCode());
        assertFalse(ex.getMessage().contains(TOKEN), "token 不能进异常 message");
        assertFalse(String.valueOf(ex).contains(TOKEN), "token 不能进异常 toString");
    }
}
