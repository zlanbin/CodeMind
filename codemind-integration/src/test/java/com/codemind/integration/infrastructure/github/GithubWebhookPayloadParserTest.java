package com.codemind.integration.infrastructure.github;

import com.codemind.integration.application.port.PullRequestPayload;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GithubWebhookPayloadParserTest {

    private final GithubWebhookPayloadParser parser =
            new GithubWebhookPayloadParser(new ObjectMapper());

    @Test
    void 解析pull_request载荷() {
        PullRequestPayload payload = parser.parsePullRequest(bytes("""
                {
                  "action": "synchronize",
                  "repository": {"full_name": "octocat/hello-world"},
                  "pull_request": {
                    "number": 42,
                    "head": {"sha": "aaa111"},
                    "base": {"sha": "bbb222"}
                  }
                }
                """));

        assertThat(payload).isEqualTo(
                new PullRequestPayload("synchronize", "octocat/hello-world", 42, "aaa111", "bbb222"));
    }

    @Test
    void 多余字段被忽略() {
        PullRequestPayload payload = parser.parsePullRequest(bytes("""
                {
                  "action": "opened",
                  "sender": {"login": "octocat"},
                  "repository": {"full_name": "a/b", "private": false},
                  "pull_request": {
                    "number": 1, "title": "t", "draft": false,
                    "head": {"sha": "h", "ref": "feature"},
                    "base": {"sha": "b", "ref": "main"}
                  }
                }
                """));

        assertThat(payload.repo()).isEqualTo("a/b");
        assertThat(payload.prNumber()).isEqualTo(1);
    }

    @Test
    void 非法JSON报错() {
        assertThatThrownBy(() -> parser.parsePullRequest(bytes("not json")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("合法 JSON");
    }

    @Test
    void 缺字段时报错并指出路径() {
        assertThatThrownBy(() -> parser.parsePullRequest(bytes("""
                {"action":"opened","repository":{"full_name":"a/b"},
                 "pull_request":{"number":1,"base":{"sha":"b"}}}
                """)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("pull_request.head.sha");

        assertThatThrownBy(() -> parser.parsePullRequest(bytes("""
                {"repository":{"full_name":"a/b"},
                 "pull_request":{"number":1,"head":{"sha":"h"},"base":{"sha":"b"}}}
                """)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("action");
    }

    private static byte[] bytes(String json) {
        return json.getBytes(StandardCharsets.UTF_8);
    }
}
