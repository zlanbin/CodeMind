package com.codemind.review.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriUtils;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 真实 GitHub REST 客户端，只给 {@code -Pgithub-write} 验收 IT 用——不是生产 SCM 实现，
 * 不被 review/integration 生产代码引用。token 只从环境变量注入，不写入仓库、不进日志。
 */
public class RealSandboxGithubClient implements SandboxGithubClient {

    private static final String JSON_MEDIA_TYPE = "application/vnd.github+json";
    private static final int PAGE_SIZE = 100;
    private static final int MAX_PAGES = 10;

    private final RestClient restClient;
    private final String baseUrl;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public RealSandboxGithubClient(RestClient.Builder builder, String baseUrl, String token) {
        this.baseUrl = Objects.requireNonNull(baseUrl, "baseUrl").replaceAll("/+$", "");
        this.restClient = builder
                .defaultHeader("X-GitHub-Api-Version", "2022-11-28")
                .defaultHeader("Authorization", "Bearer " + Objects.requireNonNull(token, "token"))
                .build();
    }

    @Override
    public String currentLogin() {
        String body = restClient.get().uri(baseUrl + "/user")
                .header("Accept", JSON_MEDIA_TYPE)
                .retrieve()
                .onStatus(HttpStatusCode::isError, (req, res) -> {
                    throw new IllegalStateException("GET /user 失败: " + res.getStatusCode());
                })
                .body(String.class);
        try {
            JsonNode node = objectMapper.readTree(body);
            if (node == null || !node.has("login") || !node.get("login").isTextual()) {
                throw new IllegalStateException("/user 响应缺少 login 字段");
            }
            return node.get("login").textValue();
        } catch (Exception e) {
            throw new IllegalStateException("/user 响应解析失败", e);
        }
    }

    @Override
    public String headSha(String repo, int prNumber) {
        URI uri = URI.create("%s/repos/%s/pulls/%d".formatted(baseUrl, encodePath(repo), prNumber));
        String body = get(uri, "%s PR #%d".formatted(repo, prNumber));
        try {
            JsonNode sha = objectMapper.readTree(body).path("head").path("sha");
            if (!sha.isTextual()) {
                throw new IllegalStateException("PR 响应缺少 head.sha");
            }
            return sha.textValue();
        } catch (Exception e) {
            throw new IllegalStateException("PR 响应解析失败", e);
        }
    }

    @Override
    public List<SandboxReview> listReviews(String repo, int prNumber) {
        List<SandboxReview> all = new ArrayList<>();
        for (int page = 1; page <= MAX_PAGES; page++) {
            URI uri = URI.create("%s/repos/%s/pulls/%d/reviews?per_page=%d&page=%d"
                    .formatted(baseUrl, encodePath(repo), prNumber, PAGE_SIZE, page));
            String body = get(uri, "%s PR #%d 的 reviews".formatted(repo, prNumber));
            List<SandboxReview> parsed = parseReviews(body);
            if (parsed.isEmpty()) {
                return all;
            }
            all.addAll(parsed);
            if (parsed.size() < PAGE_SIZE) {
                return all;
            }
        }
        throw new IllegalStateException("review 分页超过安全上限，拒绝返回不完整列表");
    }

    @Override
    public List<SandboxComment> listComments(String repo, int prNumber) {
        List<SandboxComment> all = new ArrayList<>();
        for (int page = 1; page <= MAX_PAGES; page++) {
            URI uri = URI.create("%s/repos/%s/pulls/%d/comments?per_page=%d&page=%d"
                    .formatted(baseUrl, encodePath(repo), prNumber, PAGE_SIZE, page));
            String body = get(uri, "%s PR #%d 的 comments".formatted(repo, prNumber));
            List<SandboxComment> parsed = parseComments(body);
            if (parsed.isEmpty()) {
                return all;
            }
            all.addAll(parsed);
            if (parsed.size() < PAGE_SIZE) {
                return all;
            }
        }
        throw new IllegalStateException("comment 分页超过安全上限，拒绝返回不完整列表");
    }

    @Override
    public void deleteComment(String repo, long commentId) {
        restClient.delete().uri("%s/repos/%s/pulls/comments/%d".formatted(baseUrl, encodePath(repo), commentId))
                .header("Accept", JSON_MEDIA_TYPE)
                .retrieve()
                .onStatus(HttpStatusCode::isError, (req, res) -> {
                    throw new IllegalStateException("删除评论 %d 失败: %s".formatted(commentId, res.getStatusCode()));
                })
                .toBodilessEntity();
    }

    private String get(URI uri, String target) {
        return restClient.get().uri(uri)
                .header("Accept", JSON_MEDIA_TYPE)
                .retrieve()
                .onStatus(HttpStatusCode::isError, (req, res) -> {
                    throw new IllegalStateException("读取失败 %s: %s".formatted(target, res.getStatusCode()));
                })
                .body(String.class);
    }

    private List<SandboxReview> parseReviews(String body) {
        try {
            JsonNode root = objectMapper.readTree(body);
            if (root == null || !root.isArray()) {
                throw new IllegalStateException("review 列表响应不是数组");
            }
            List<SandboxReview> result = new ArrayList<>();
            for (JsonNode node : root) {
                if (!node.has("id") || !node.has("user") || !node.get("user").has("login")) {
                    throw new IllegalStateException("review 列表响应缺字段");
                }
                String bodyText = node.has("body") && node.get("body").isTextual() ? node.get("body").textValue() : "";
                result.add(new SandboxReview(node.get("id").asLong(), bodyText, node.get("user").get("login").asText()));
            }
            return result;
        } catch (Exception e) {
            throw new IllegalStateException("review 列表响应解析失败", e);
        }
    }

    private List<SandboxComment> parseComments(String body) {
        try {
            JsonNode root = objectMapper.readTree(body);
            if (root == null || !root.isArray()) {
                throw new IllegalStateException("comment 列表响应不是数组");
            }
            List<SandboxComment> result = new ArrayList<>();
            for (JsonNode node : root) {
                if (!node.has("id") || !node.has("user") || !node.get("user").has("login")) {
                    throw new IllegalStateException("comment 列表响应缺字段");
                }
                String bodyText = node.has("body") && node.get("body").isTextual() ? node.get("body").textValue() : "";
                Long reviewId = node.has("pull_request_review_id") && node.get("pull_request_review_id").isIntegralNumber()
                        ? node.get("pull_request_review_id").asLong() : null;
                String path = node.path("path").isTextual() ? node.get("path").textValue() : null;
                Integer line = node.path("line").isIntegralNumber() ? node.get("line").asInt() : null;
                result.add(new SandboxComment(node.get("id").asLong(), reviewId, bodyText,
                        node.get("user").get("login").asText(), path, line));
            }
            return result;
        } catch (Exception e) {
            throw new IllegalStateException("comment 列表响应解析失败", e);
        }
    }

    private static String encodePath(String path) {
        return Arrays.stream(path.split("/", -1))
                .map(segment -> UriUtils.encodePathSegment(segment, StandardCharsets.UTF_8))
                .collect(Collectors.joining("/"));
    }
}
