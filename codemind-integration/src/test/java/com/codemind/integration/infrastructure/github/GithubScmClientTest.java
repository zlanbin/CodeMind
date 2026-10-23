package com.codemind.integration.infrastructure.github;

import com.codemind.integration.application.port.DraftComment;
import com.codemind.integration.application.port.ExistingReview;
import com.codemind.integration.application.port.ReviewDraft;
import com.codemind.integration.application.port.ScmException;
import com.codemind.integration.application.port.ScmReviewPublisher;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpMethod;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.List;
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

    /** Catches missing commit pinning, per-comment POSTs, wrong side, and URI-encoding JSON paths. */
    @Test
    void postsOneCommentReviewAtRequestedCommit() {
        ScmReviewPublisher publisher = clientWithToken(TOKEN);
        server.expect(requestTo(BASE_URL + "/repos/owner%20name/repo%23one/pulls/7/reviews"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer " + TOKEN))
                .andExpect(header("X-GitHub-Api-Version", "2022-11-28"))
                .andExpect(header("Accept", "application/vnd.github+json"))
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(content().json("""
                        {"commit_id":"head-sha-123","event":"COMMENT","body":"summary",
                         "comments":[
                           {"path":"src/My Dir/A#B.java","line":12,"side":"RIGHT","body":"first"},
                           {"path":"src/C.java","line":21,"side":"RIGHT","body":"second"}
                         ]}
                        """, true))
                .andRespond(withStatus(HttpStatus.CREATED).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"id\":901,\"body\":\"published summary\",\"state\":\"COMMENTED\"}"));

        ExistingReview actual = publisher.createReview("owner name/repo#one", 7,
                new ReviewDraft("head-sha-123", "summary", List.of(
                        new DraftComment("src/My Dir/A#B.java", 12, "first"),
                        new DraftComment("src/C.java", 21, "second"))));

        assertEquals(new ExistingReview(901, "published summary"), actual);
        server.verify();
    }

    /** Catches stopping after a short page, losing earlier pages, or following untrusted Link URLs. */
    @Test
    void listsAllPages() {
        ScmReviewPublisher publisher = clientWithToken(TOKEN);
        server.expect(requestTo(BASE_URL + "/repos/owner%20name/repo%23one/pulls/7/reviews?per_page=100&page=1"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("Authorization", "Bearer " + TOKEN))
                .andExpect(header("X-GitHub-Api-Version", "2022-11-28"))
                .andExpect(header("Accept", "application/vnd.github+json"))
                .andRespond(withSuccess("[{\"id\":11,\"body\":\"first\",\"state\":\"COMMENTED\"}]",
                        MediaType.APPLICATION_JSON)
                        .header("Link", "<https://untrusted.test/reviews?page=2>; rel=\"next\", "
                                + "<https://untrusted.test/reviews?page=3>; rel=\"last\""));
        server.expect(requestTo(BASE_URL + "/repos/owner%20name/repo%23one/pulls/7/reviews?per_page=100&page=2"))
                .andRespond(withSuccess("[{\"id\":12,\"body\":\"second\",\"state\":\"APPROVED\"}]",
                        MediaType.APPLICATION_JSON)
                        .header("Link", "<https://untrusted.test/reviews?page=3>; rel=\"next\""));
        server.expect(requestTo(BASE_URL + "/repos/owner%20name/repo%23one/pulls/7/reviews?per_page=100&page=3"))
                .andRespond(withSuccess("[{\"id\":13,\"body\":\"last\",\"state\":\"DISMISSED\"}]",
                        MediaType.APPLICATION_JSON)
                        .header("Link", "<https://untrusted.test/reviews?page=2>; rel=\"prev\""));

        assertEquals(List.of(new ExistingReview(11, "first"), new ExistingReview(12, "second"),
                new ExistingReview(13, "last")), publisher.listReviews("owner name/repo#one", 7));
        server.verify();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "<https://untrusted.test/reviews?page=2>; rel=\"next\"; type=\"application/json\"",
            "<https://untrusted.test/reviews?page=2>; rel=\"next last\"",
            "<https://untrusted.test/reviews?page=2>; type=\"application/json\"; rel=next",
            "<https://untrusted.test/reviews?page=2>; title=\"a,b; \\\"quoted\\\"\"; rel=\"next\"",
            "<https://untrusted.test/reviews?values=a,b&page=2>; rel=\"next\""
    })
    void recognizesLinkParametersAndMultipleRelationTokens(String link) {
        ScmReviewPublisher publisher = clientWithToken(TOKEN);
        server.expect(requestTo(BASE_URL + "/repos/owner/repo/pulls/7/reviews?per_page=100&page=1"))
                .andRespond(withSuccess("[{\"id\":1,\"body\":\"first\",\"state\":\"COMMENTED\"}]",
                        MediaType.APPLICATION_JSON)
                        .header("Link", link));
        server.expect(requestTo(BASE_URL + "/repos/owner/repo/pulls/7/reviews?per_page=100&page=2"))
                .andRespond(withSuccess("[{\"id\":2,\"body\":\"second\",\"state\":\"APPROVED\"}]",
                        MediaType.APPLICATION_JSON));

        assertEquals(List.of(new ExistingReview(1, "first"), new ExistingReview(2, "second")),
                publisher.listReviews("owner/repo", 7));
        server.verify();
    }

    @Test
    void recognizesNextInLaterLinkHeaderField() {
        ScmReviewPublisher publisher = clientWithToken(TOKEN);
        server.expect(requestTo(BASE_URL + "/repos/owner/repo/pulls/7/reviews?per_page=100&page=1"))
                .andRespond(withSuccess("[{\"id\":1,\"body\":\"first\",\"state\":\"COMMENTED\"}]",
                        MediaType.APPLICATION_JSON)
                        .header("Link", "<" + BASE_URL + "/reviews?page=2>; rel=\"last\"",
                                "<" + BASE_URL + "/reviews?page=2>; rel=\"next\""));
        server.expect(requestTo(BASE_URL + "/repos/owner/repo/pulls/7/reviews?per_page=100&page=2"))
                .andRespond(withSuccess("[{\"id\":2,\"body\":\"second\",\"state\":\"COMMENTED\"}]",
                        MediaType.APPLICATION_JSON));

        assertEquals(List.of(new ExistingReview(1, "first"), new ExistingReview(2, "second")),
                publisher.listReviews("owner/repo", 7));
        server.verify();
    }

    @ParameterizedTest
    @ValueSource(strings = {"broken-link", "<https://untrusted.test/page=2>; rel=\"next",
            "<https://untrusted.test/page=2>; rel=", "<https://untrusted.test/page=2>; rel=\"\"",
            "<https://untrusted.test/page=2>; rel=\"prev\"; rel=\"next\"",
            "<https://untrusted.test/page=2>; rel=\"next\", broken-link"})
    void malformedLinkMetadataFailsClosed(String link) {
        ScmReviewPublisher publisher = clientWithToken(TOKEN);
        server.expect(requestTo(BASE_URL + "/repos/owner/repo/pulls/7/reviews?per_page=100&page=1"))
                .andRespond(withSuccess("[{\"id\":1,\"body\":\"first\",\"state\":\"COMMENTED\"}]",
                        MediaType.APPLICATION_JSON).header("Link", link));

        assertEquals(0, assertThrows(ScmException.class,
                () -> publisher.listReviews("owner/repo", 7)).statusCode());
        server.verify();
    }

    @Test
    void nextInTitleOrDifferentRelationDoesNotTriggerAnotherPage() {
        ScmReviewPublisher publisher = clientWithToken(TOKEN);
        server.expect(requestTo(BASE_URL + "/repos/owner/repo/pulls/7/reviews?per_page=100&page=1"))
                .andRespond(withSuccess("[{\"id\":1,\"body\":\"first\",\"state\":\"COMMENTED\"}]",
                        MediaType.APPLICATION_JSON)
                        .header("Link", "<" + BASE_URL + "/reviews?page=2>; title=\"next\"; rel=\"next-page last\""));

        assertEquals(List.of(new ExistingReview(1, "first")), publisher.listReviews("owner/repo", 7));
        server.verify();
    }

    @Test
    void tenthPageWithParameterizedNextLinkFailsClosed() {
        ScmReviewPublisher publisher = clientWithToken(TOKEN);
        for (int page = 1; page <= 10; page++) {
            var response = withSuccess("[{\"id\":" + page + ",\"body\":\"page\",\"state\":\"COMMENTED\"}]",
                    MediaType.APPLICATION_JSON);
            if (page < 10) {
                response.header("Link", "<" + BASE_URL + "/reviews?page=" + (page + 1)
                        + ">; type=\"application/json\"; rel=\"next last\"");
            } else {
                response.header("Link", "<" + BASE_URL + "/reviews?page=11>; rel=\"next last\"; type=\"application/json\"");
            }
            server.expect(requestTo(BASE_URL + "/repos/owner/repo/pulls/7/reviews?per_page=100&page=" + page))
                    .andRespond(response);
        }

        assertThrows(ScmException.class, () -> publisher.listReviews("owner/repo", 7));
        server.verify();
    }

    /** Catches treating a pending hidden marker as proof of publication or keeping null bodies. */
    @Test
    void filtersPendingReviews() {
        ScmReviewPublisher publisher = clientWithToken(TOKEN);
        server.expect(requestTo(BASE_URL + "/repos/owner/repo/pulls/7/reviews?per_page=100&page=1"))
                .andRespond(withSuccess("""
                        [{"id":1,"body":"<!-- codemind:task -->","state":"PENDING"},
                         {"id":2,"body":null,"state":"COMMENTED"},
                         {"id":3,"body":"approved","state":"APPROVED"},
                         {"id":4,"body":"changes","state":"CHANGES_REQUESTED"}]
                        """, MediaType.APPLICATION_JSON));

        assertEquals(List.of(new ExistingReview(2, ""), new ExistingReview(3, "approved"),
                new ExistingReview(4, "changes")), publisher.listReviews("owner/repo", 7));
        server.verify();
    }

    /** Catches returning partial deduplication evidence at the ten-page safety limit. */
    @Test
    void tenthPageWithNextLinkFailsClosed() {
        ScmReviewPublisher publisher = clientWithToken(TOKEN);
        expectTenReviewPages(true);

        assertThrows(ScmException.class, () -> publisher.listReviews("owner/repo", 7));
        server.verify();
    }

    @Test
    void tenthPageWithoutNextLinkSucceeds() {
        ScmReviewPublisher publisher = clientWithToken(TOKEN);
        expectTenReviewPages(false);

        List<ExistingReview> reviews = publisher.listReviews("owner/repo", 7);

        assertEquals(10, reviews.size());
        assertEquals(new ExistingReview(1, "page"), reviews.getFirst());
        assertEquals(new ExistingReview(10, "page"), reviews.getLast());
        server.verify();
    }

    private void expectTenReviewPages(boolean nextOnLastPage) {
        for (int page = 1; page <= 10; page++) {
            var response = withSuccess("[{\"id\":" + page + ",\"body\":\"page\",\"state\":\"COMMENTED\"}]",
                    MediaType.APPLICATION_JSON);
            if (page < 10 || nextOnLastPage) {
                response.header("Link", "<" + BASE_URL + "/reviews?page=" + (page + 1) + ">; rel=\"next\"");
            }
            server.expect(requestTo(BASE_URL + "/repos/owner/repo/pulls/7/reviews?per_page=100&page=" + page))
                    .andRespond(response);
        }
    }

    @Test
    void emptyPageEndsListing() {
        ScmReviewPublisher publisher = clientWithToken(TOKEN);
        server.expect(requestTo(BASE_URL + "/repos/owner/repo/pulls/7/reviews?per_page=100&page=1"))
                .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON)
                        .header("Link", "<" + BASE_URL + "/reviews?page=2>; rel=\"next\""));

        assertEquals(List.of(), publisher.listReviews("owner/repo", 7));
        server.verify();
    }

    /** Catches using the filtered list's size to end pagination. */
    @Test
    void pendingOnlyPageDoesNotEndListing() {
        ScmReviewPublisher publisher = clientWithToken(TOKEN);
        server.expect(requestTo(BASE_URL + "/repos/owner/repo/pulls/7/reviews?per_page=100&page=1"))
                .andRespond(withSuccess("[{\"id\":1,\"body\":\"pending\",\"state\":\"PENDING\"}]",
                        MediaType.APPLICATION_JSON)
                        .header("Link", "<" + BASE_URL + "/reviews?page=2>; rel=\"next\""));
        server.expect(requestTo(BASE_URL + "/repos/owner/repo/pulls/7/reviews?per_page=100&page=2"))
                .andRespond(withSuccess("[{\"id\":2,\"body\":\"submitted\",\"state\":\"COMMENTED\"}]",
                        MediaType.APPLICATION_JSON));

        assertEquals(List.of(new ExistingReview(2, "submitted")), publisher.listReviews("owner/repo", 7));
        server.verify();
    }

    @Test
    void createsSummaryOnlyReviewAndNormalizesNullBody() {
        ScmReviewPublisher publisher = clientWithToken(TOKEN);
        server.expect(requestTo(BASE_URL + "/repos/owner/repo/pulls/7/reviews"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().json("""
                        {"commit_id":"head","event":"COMMENT","body":"summary","comments":[]}
                        """, true))
                .andRespond(withStatus(HttpStatus.CREATED).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"id\":31,\"body\":null,\"state\":\"COMMENTED\"}"));

        assertEquals(new ExistingReview(31, ""), publisher.createReview("owner/repo", 7,
                new ReviewDraft("head", "summary", List.of())));
        server.verify();
    }

    /** Catches coercing malformed/empty success responses into a made-up zero ID. */
    @ParameterizedTest
    @ValueSource(strings = {"", "null", "{}", "[]", "not-json", "{\"id\":0}", "{\"id\":-1}",
            "{\"id\":\"12\"}", "{\"id\":1.5}", "{\"id\":9223372036854775808}",
            "{\"id\":12,\"body\":{}}"})
    void rejectsEmptyOrMalformedCreationResponses(String response) {
        ScmReviewPublisher publisher = clientWithToken(TOKEN);
        server.expect(requestTo(BASE_URL + "/repos/owner/repo/pulls/7/reviews"))
                .andRespond(withStatus(HttpStatus.CREATED).contentType(MediaType.APPLICATION_JSON).body(response));

        assertThrows(ScmException.class, () -> publisher.createReview("owner/repo", 7,
                new ReviewDraft("head", "summary", List.of())));
        server.verify();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "null", "{}", "not-json", "[{}]", "[{\"id\":1,\"state\":null}]",
            "[{\"id\":1,\"state\":\"COMMENTED\",\"body\":false}]"})
    void malformedListResponsesFailClosed(String response) {
        ScmReviewPublisher publisher = clientWithToken(TOKEN);
        server.expect(requestTo(BASE_URL + "/repos/owner/repo/pulls/7/reviews?per_page=100&page=1"))
                .andRespond(withSuccess(response, MediaType.APPLICATION_JSON));

        assertThrows(ScmException.class, () -> publisher.listReviews("owner/repo", 7));
        server.verify();
    }

    @ParameterizedTest
    @ValueSource(strings = {"[] trailing-garbage", "[] {}", "[] []",
            "[{\"id\":1,\"body\":\"first\",\"state\":\"COMMENTED\"}] true"})
    void rejectsTrailingTokensInListResponse(String response) {
        ScmReviewPublisher publisher = clientWithToken(TOKEN);
        server.expect(requestTo(BASE_URL + "/repos/owner/repo/pulls/7/reviews?per_page=100&page=1"))
                .andRespond(withSuccess(response, MediaType.APPLICATION_JSON));

        assertEquals(0, assertThrows(ScmException.class,
                () -> publisher.listReviews("owner/repo", 7)).statusCode());
        server.verify();
    }

    @ParameterizedTest
    @ValueSource(strings = {"{\"id\":123,\"body\":\"published\"} trailing-garbage",
            "{\"id\":123,\"body\":\"published\"} {}",
            "{\"id\":123,\"body\":\"published\"} []"})
    void rejectsTrailingTokensInCreationResponse(String response) {
        ScmReviewPublisher publisher = clientWithToken(TOKEN);
        server.expect(requestTo(BASE_URL + "/repos/owner/repo/pulls/7/reviews"))
                .andRespond(withStatus(HttpStatus.CREATED).contentType(MediaType.APPLICATION_JSON).body(response));

        assertEquals(0, assertThrows(ScmException.class, () -> publisher.createReview("owner/repo", 7,
                new ReviewDraft("head", "summary", List.of()))).statusCode());
        server.verify();
    }

    @Test
    void acceptsTrailingWhitespaceInReviewResponses() {
        ScmReviewPublisher publisher = clientWithToken(TOKEN);
        server.expect(requestTo(BASE_URL + "/repos/owner/repo/pulls/7/reviews?per_page=100&page=1"))
                .andRespond(withSuccess("[{\"id\":1,\"body\":\"first\",\"state\":\"COMMENTED\"}] \r\n\t",
                        MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE_URL + "/repos/owner/repo/pulls/7/reviews"))
                .andRespond(withStatus(HttpStatus.CREATED).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"id\":123,\"body\":\"published\"} \r\n\t"));

        assertEquals(List.of(new ExistingReview(1, "first")), publisher.listReviews("owner/repo", 7));
        assertEquals(new ExistingReview(123, "published"), publisher.createReview("owner/repo", 7,
                new ReviewDraft("head", "summary", List.of())));
        server.verify();
    }

    @Test
    void maps422AndNetworkErrors() {
        ScmReviewPublisher publisher = clientWithToken(TOKEN);
        server.expect(requestTo(BASE_URL + "/repos/owner/repo/pulls/7/reviews"))
                .andRespond(withStatus(HttpStatus.UNPROCESSABLE_ENTITY));
        server.expect(requestTo(BASE_URL + "/repos/owner/repo/pulls/7/reviews?per_page=100&page=1"))
                .andRespond(withException(new IOException("connection timed out")));
        server.expect(requestTo(BASE_URL + "/repos/owner/repo/pulls/7/reviews"))
                .andRespond(withException(new IOException("connection reset")));

        assertEquals(422, assertThrows(ScmException.class, () -> publisher.createReview("owner/repo", 7,
                new ReviewDraft("head", "summary", List.of()))).statusCode());
        assertEquals(0, assertThrows(ScmException.class, () -> publisher.listReviews("owner/repo", 7)).statusCode());
        assertEquals(0, assertThrows(ScmException.class, () -> publisher.createReview("owner/repo", 7,
                new ReviewDraft("head", "summary", List.of()))).statusCode());
        server.verify();
    }

    @Test
    void laterPageErrorDoesNotReturnPartialList() {
        ScmReviewPublisher publisher = clientWithToken(TOKEN);
        server.expect(requestTo(BASE_URL + "/repos/owner/repo/pulls/7/reviews?per_page=100&page=1"))
                .andRespond(withSuccess("[{\"id\":1,\"body\":\"first\",\"state\":\"COMMENTED\"}]",
                        MediaType.APPLICATION_JSON)
                        .header("Link", "<" + BASE_URL + "/reviews?page=2>; rel=\"next\""));
        server.expect(requestTo(BASE_URL + "/repos/owner/repo/pulls/7/reviews?per_page=100&page=2"))
                .andRespond(withStatus(HttpStatus.FORBIDDEN));

        assertEquals(403, assertThrows(ScmException.class, () -> publisher.listReviews("owner/repo", 7)).statusCode());
        server.verify();
    }

    /** Catches leaking raw error bodies, transport causes, conversion diagnostics or headers to logs. */
    @Test
    void neverLeaksToken() {
        Logger logger = (Logger) LoggerFactory.getLogger(GithubScmClient.class);
        ListAppender<ILoggingEvent> logs = new ListAppender<>();
        logs.start();
        logger.addAppender(logs);
        try {
            ScmReviewPublisher publisher = clientWithToken(TOKEN);
            server.expect(requestTo(BASE_URL + "/repos/owner/repo/pulls/7/reviews"))
                    .andRespond(withStatus(HttpStatus.UNAUTHORIZED).body("Authorization: Bearer " + TOKEN));
            server.expect(requestTo(BASE_URL + "/repos/owner/repo/pulls/7/reviews?per_page=100&page=1"))
                    .andRespond(withException(new IOException("Authorization: Bearer " + TOKEN)));
            server.expect(requestTo(BASE_URL + "/repos/owner/repo/pulls/7/reviews"))
                    .andRespond(withStatus(HttpStatus.CREATED).contentType(MediaType.APPLICATION_JSON)
                            .body("{\"id\": \"" + TOKEN + "\" invalid-json"));
            List<ScmException> errors = List.of(
                    assertThrows(ScmException.class, () -> publisher.createReview("owner/repo", 7,
                            new ReviewDraft("head", "summary", List.of()))),
                    assertThrows(ScmException.class, () -> publisher.listReviews("owner/repo", 7)),
                    assertThrows(ScmException.class, () -> publisher.createReview("owner/repo", 7,
                            new ReviewDraft("head", "summary", List.of()))));
            for (ScmException error : errors) {
                StringWriter trace = new StringWriter();
                error.printStackTrace(new PrintWriter(trace));
                assertFalse(trace.toString().contains(TOKEN), "token must not enter exception/cause chain");
            }
            for (ILoggingEvent event : logs.list) {
                assertFalse(event.getFormattedMessage().contains(TOKEN), "token must not enter client logs");
                assertNull(event.getThrowableProxy(), "client must not log raw transport/conversion exceptions");
            }
            server.verify();
        } finally {
            logger.detachAppender(logs);
            logs.stop();
        }
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
