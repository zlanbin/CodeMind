package com.codemind.integration.infrastructure.github;

import com.codemind.integration.application.port.ExistingReview;
import com.codemind.integration.application.port.ReviewDraft;
import com.codemind.integration.application.port.ScmReviewPublisher;
import com.codemind.integration.application.port.ScmClient;
import java.util.List;
import com.codemind.integration.application.port.ScmException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.util.UriUtils;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * GitHub REST v3 只读适配器（M7a）。
 *
 * <p>两种媒体类型：
 * <ul>
 *   <li>{@code application/vnd.github.v3.diff} —— PR 的整份 unified diff，正好喂 {@code DiffParser}，
 *       比逐文件拼 {@code pulls/{n}/files} 省事；</li>
 *   <li>{@code application/vnd.github.raw} —— 文件原文，省掉 base64 解码。</li>
 * </ul>
 *
 * <p>超时不在此类配置：{@code RestClient.Builder} 的 requestFactory 由装配层设置，
 * 这里再设会顶掉测试装的 mock（见 M7a ledger 的 Ruling）。
 */
public class GithubScmClient implements ScmClient, ScmReviewPublisher {

    @Override
    public List<ExistingReview> listReviews(String repo, int pr) {
        List<ExistingReview> submitted = new ArrayList<>();
        for (int page = 1; page <= MAX_PAGES; page++) {
            ReviewPage response = getReviewPage(repo, pr, page);
            if (response.reviews().isEmpty()) {
                return submitted;
            }
            response.reviews().stream()
                    .filter(review -> !"PENDING".equals(review.state()))
                    .map(review -> new ExistingReview(review.id(), nullToEmpty(review.body())))
                    .forEach(submitted::add);
            if (!response.hasNext()) {
                return submitted;
            }
        }
        throw new ScmException("GitHub review 分页超过安全上限，拒绝返回不完整列表", 0);
    }

    @Override
    public ExistingReview createReview(String repo, int pr, ReviewDraft draft) {
        URI uri = reviewUri(repo, pr);
        ObjectNode payload = objectMapper.createObjectNode()
                .put("commit_id", draft.commitId())
                .put("event", "COMMENT")
                .put("body", draft.body());
        ArrayNode comments = payload.putArray("comments");
        for (var comment : draft.comments()) {
            comments.addObject()
                    .put("path", comment.path())
                    .put("line", comment.line())
                    .put("side", "RIGHT")
                    .put("body", comment.body());
        }
        try {
            ResponseEntity<String> response = restClient.post()
                    .uri(uri)
                    .header("Accept", JSON_MEDIA_TYPE)
                    .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                    .body(objectMapper.writeValueAsString(payload))
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, (request, clientResponse) -> {
                        throw new ScmException(describe(clientResponse.getStatusCode().value(),
                                "%s PR #%d 的 review".formatted(repo, pr)),
                                clientResponse.getStatusCode().value());
                    })
                    .toEntity(String.class);
            return parseCreatedReview(response.getBody(), repo, pr);
        } catch (ResourceAccessException e) {
            throw networkFailure("%s PR #%d 的 review".formatted(repo, pr), e);
        } catch (JsonProcessingException | RestClientException e) {
            if (e instanceof ScmException scmException) {
                throw scmException;
            }
            throw malformedResponse("GitHub review 创建响应无法解析", repo, pr, e);
        }
    }

    private ReviewPage getReviewPage(String repo, int pr, int page) {
        URI uri = URI.create("%s/repos/%s/pulls/%d/reviews?per_page=%d&page=%d".formatted(
                baseUrl, encodePath(repo), pr, PAGE_SIZE, page));
        try {
            ResponseEntity<String> response = restClient.get()
                    .uri(uri)
                    .header("Accept", JSON_MEDIA_TYPE)
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, (request, clientResponse) -> {
                        throw new ScmException(describe(clientResponse.getStatusCode().value(),
                                "%s PR #%d 的 reviews".formatted(repo, pr)),
                                clientResponse.getStatusCode().value());
                    })
                    .toEntity(String.class);
            try {
                return new ReviewPage(parseReviewList(response.getBody()),
                        hasNext(response.getHeaders().get("Link")));
            } catch (JsonProcessingException | IllegalArgumentException e) {
                throw malformedResponse("GitHub review 列表响应畸形", repo, pr, e);
            }
        } catch (ResourceAccessException e) {
            throw networkFailure("%s PR #%d 的 reviews".formatted(repo, pr), e);
        } catch (RestClientException e) {
            throw malformedResponse("GitHub review 列表响应无法解析", repo, pr, e);
        }
    }

    private ExistingReview parseCreatedReview(String body, String repo, int pr) {
        try {
            if (body == null || body.isBlank()) {
                throw new IllegalArgumentException("empty response");
            }
            JsonNode node = objectMapper.readTree(body);
            if (node == null || !node.isObject() || !node.has("id") || !node.get("id").isIntegralNumber()
                    || !node.get("id").canConvertToLong() || node.get("id").asLong() <= 0
                    || (node.has("body") && !node.get("body").isNull()
                    && !node.get("body").isTextual())) {
                throw new IllegalArgumentException("missing or invalid review fields");
            }
            return new ExistingReview(node.get("id").asLong(),
                    node.has("body") && !node.get("body").isNull() ? node.get("body").textValue() : "");
        } catch (JsonProcessingException | IllegalArgumentException e) {
            throw malformedResponse("GitHub review 创建响应畸形，未确认发布结果", repo, pr, e);
        }
    }

    private List<ReviewRecord> parseReviewList(String body) throws JsonProcessingException {
        if (body == null || body.isBlank()) {
            throw new IllegalArgumentException("empty response");
        }
        JsonNode root = objectMapper.readTree(body);
        if (root == null || !root.isArray()) {
            throw new IllegalArgumentException("review response is not an array");
        }
        List<ReviewRecord> reviews = new ArrayList<>();
        for (JsonNode node : root) {
            if (!node.isObject() || !node.has("id") || !node.get("id").isIntegralNumber()
                    || !node.get("id").canConvertToLong() || node.get("id").asLong() <= 0
                    || !node.has("state") || !node.get("state").isTextual()
                    || (node.has("body") && !node.get("body").isNull() && !node.get("body").isTextual())) {
                throw new IllegalArgumentException("invalid review item");
            }
            reviews.add(new ReviewRecord(node.get("id").asLong(),
                    node.has("body") && !node.get("body").isNull() ? node.get("body").textValue() : "",
                    node.get("state").textValue()));
        }
        return reviews;
    }

    /** Parse every field completely: commas in URI references or quoted parameters are not separators. */
    private static boolean hasNext(List<String> fields) {
        if (fields == null) {
            return false;
        }
        boolean next = false;
        for (String field : fields) {
            if (field == null || field.isBlank()) {
                throw new IllegalArgumentException("empty Link field");
            }
            int offset = 0;
            while (offset < field.length()) {
                var target = LINK_TARGET.matcher(field).region(offset, field.length());
                if (!target.lookingAt()) {
                    throw new IllegalArgumentException("invalid Link target");
                }
                URI.create(target.group(1));
                offset = target.end();
                boolean hasRelation = false;
                while (offset < field.length() && field.charAt(offset) == ';') {
                    var parameter = LINK_PARAMETER.matcher(field).region(offset, field.length());
                    if (!parameter.lookingAt()) {
                        throw new IllegalArgumentException("invalid Link parameter");
                    }
                    if ("rel".equalsIgnoreCase(parameter.group(1))) {
                        String relation = parameter.group(2);
                        if (hasRelation || relation == null) {
                            throw new IllegalArgumentException("ambiguous Link relation");
                        }
                        hasRelation = true;
                        if (relation.startsWith("\"")) {
                            relation = relation.substring(1, relation.length() - 1)
                                    .replaceAll("\\\\(.)", "$1");
                        }
                        if (relation.isBlank()) {
                            throw new IllegalArgumentException("empty Link relation");
                        }
                        for (String token : relation.trim().split("[ \\t]+")) {
                            next |= "next".equalsIgnoreCase(token);
                        }
                    }
                    offset = parameter.end();
                }
                if (!hasRelation) {
                    throw new IllegalArgumentException("missing Link relation");
                }
                if (offset < field.length()) {
                    if (field.charAt(offset) != ',' || ++offset == field.length()
                            || field.substring(offset).isBlank()) {
                        throw new IllegalArgumentException("invalid Link separator");
                    }
                }
            }
        }
        return next;
    }

    private URI reviewUri(String repo, int pr) {
        return URI.create("%s/repos/%s/pulls/%d/reviews".formatted(baseUrl, encodePath(repo), pr));
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static ScmException networkFailure(String target, Throwable cause) {
        return new ScmException("访问 GitHub 失败（网络或超时）: " + target, 0,
                new RuntimeException("GitHub transport failure"));
    }

    private static ScmException malformedResponse(String message, String repo, int pr, Throwable cause) {
        return new ScmException("%s: %s PR #%d".formatted(message, repo, pr), 0,
                new RuntimeException("GitHub response format failure"));
    }

    private record ReviewPage(List<ReviewRecord> reviews, boolean hasNext) {}

    private record ReviewRecord(long id, String body, String state) {}

    private static final Logger log = LoggerFactory.getLogger(GithubScmClient.class);

    private static final String DIFF_MEDIA_TYPE = "application/vnd.github.v3.diff";
    private static final String RAW_MEDIA_TYPE = "application/vnd.github.raw";
    private static final String API_VERSION = "2022-11-28";
    private static final String JSON_MEDIA_TYPE = "application/vnd.github+json";
    private static final int PAGE_SIZE = 100;
    private static final int MAX_PAGES = 10;
    private static final String HTTP_TOKEN = "[!#$%&'*+.^_`|~0-9A-Za-z-]+";
    private static final java.util.regex.Pattern LINK_TARGET = java.util.regex.Pattern.compile(
            "[ \\t]*<([^<>\\r\\n]*)>[ \\t]*");
    private static final java.util.regex.Pattern LINK_PARAMETER = java.util.regex.Pattern.compile(
            ";[ \\t]*(" + HTTP_TOKEN + ")(?:[ \\t]*=[ \\t]*(" + HTTP_TOKEN
                    + "|\"(?:[^\"\\\\\\r\\n]|\\\\[^\\r\\n])*\"))?[ \\t]*");

    private final RestClient restClient;
    private final ObjectMapper objectMapper = new ObjectMapper()
            .enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private final String baseUrl;

    /**
     * @param builder GitHub 专用的 builder（超时等传输配置已由装配层设好）
     * @param baseUrl GitHub API 根地址
     * @param token   访问令牌；空串表示匿名（只能读公开仓库）
     */
    public GithubScmClient(RestClient.Builder builder, String baseUrl, String token) {
        Objects.requireNonNull(builder, "builder");
        this.baseUrl = Objects.requireNonNull(baseUrl, "baseUrl").replaceAll("/+$", "");
        RestClient.Builder configured = builder
                .defaultHeader("X-GitHub-Api-Version", API_VERSION);
        if (token == null || token.isBlank()) {
            log.warn("未配置 GITHUB_TOKEN，GitHub 调用将以匿名身份发出，只能读公开仓库且限流很低");
        } else {
            configured = configured.defaultHeader("Authorization", "Bearer " + token);
        }
        this.restClient = configured.build();
    }

    @Override
    public String fetchPullRequestDiff(String repo, int prNumber) {
        URI uri = URI.create("%s/repos/%s/pulls/%d".formatted(baseUrl, encodePath(repo), prNumber));
        return get(uri, DIFF_MEDIA_TYPE, "%s PR #%d 的 diff".formatted(repo, prNumber));
    }

    @Override
    public String fetchFileContent(String repo, String path, String ref) {
        URI uri = URI.create("%s/repos/%s/contents/%s?ref=%s".formatted(baseUrl, encodePath(repo),
                encodePath(path), UriUtils.encodeQueryParam(ref, StandardCharsets.UTF_8)));
        return get(uri, RAW_MEDIA_TYPE, "%s 的文件 %s@%s".formatted(repo, path, ref));
    }

    /**
     * 逐段编码、保留 {@code /}：Git 文件名可含空格、{@code #}、{@code ?}，原样拼进 URI 会被截成
     * fragment / query。编好后以 {@link URI} 传给 RestClient，它不会再编一遍。
     */
    private static String encodePath(String path) {
        return Arrays.stream(path.split("/", -1))
                .map(segment -> UriUtils.encodePathSegment(segment, StandardCharsets.UTF_8))
                .collect(Collectors.joining("/"));
    }

    /**
     * @param target 出错时写进异常 message 的人类可读目标描述（含仓库与路径，便于排查）
     */
    private String get(URI uri, String acceptMediaType, String target) {
        try {
            String body = restClient.get()
                    .uri(uri)
                    .header("Accept", acceptMediaType)
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, (request, response) -> {
                        throw new ScmException(
                                describe(response.getStatusCode().value(), target),
                                response.getStatusCode().value());
                    })
                    .body(String.class);
            return body == null ? "" : body;
        } catch (ResourceAccessException e) {
            // 网络层失败（连不上、超时）：statusCode 用 0，原异常留作 cause。
            // e 的 message 来自传输层，不含请求头，不会泄漏 token。
            throw new ScmException("访问 GitHub 失败（网络或超时）: " + target, 0, e);
        }
    }

    /** 按状态码给出可操作的说明。绝不拼入 token。 */
    private static String describe(int status, String target) {
        String hint = switch (status) {
            case 401 -> "GitHub 拒绝认证（401），检查 GITHUB_TOKEN 是否有效";
            case 403 -> "GitHub 拒绝访问（403），可能是 token 权限不足或触发限流";
            case 404 -> "GitHub 返回不存在（404），检查仓库名、PR 序号与路径是否正确";
            case 406 -> "GitHub 拒绝返回（406），通常是 diff 过大超出平台上限";
            default -> "GitHub 返回错误状态 " + status;
        };
        return "%s：%s".formatted(hint, target);
    }
}
