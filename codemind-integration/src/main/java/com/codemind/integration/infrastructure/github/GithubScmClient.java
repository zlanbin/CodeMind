package com.codemind.integration.infrastructure.github;

import com.codemind.integration.application.port.ScmClient;
import com.codemind.integration.application.port.ScmException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.ResourceAccessException;

import java.util.Objects;

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
public class GithubScmClient implements ScmClient {

    private static final Logger log = LoggerFactory.getLogger(GithubScmClient.class);

    private static final String DIFF_MEDIA_TYPE = "application/vnd.github.v3.diff";
    private static final String RAW_MEDIA_TYPE = "application/vnd.github.raw";
    private static final String API_VERSION = "2022-11-28";

    private final RestClient restClient;

    /**
     * @param builder GitHub 专用的 builder（超时等传输配置已由装配层设好）
     * @param baseUrl GitHub API 根地址
     * @param token   访问令牌；空串表示匿名（只能读公开仓库）
     */
    public GithubScmClient(RestClient.Builder builder, String baseUrl, String token) {
        Objects.requireNonNull(builder, "builder");
        RestClient.Builder configured = builder
                .baseUrl(Objects.requireNonNull(baseUrl, "baseUrl"))
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
        String uri = "/repos/%s/pulls/%d".formatted(repo, prNumber);
        return get(uri, DIFF_MEDIA_TYPE, "%s PR #%d 的 diff".formatted(repo, prNumber));
    }

    @Override
    public String fetchFileContent(String repo, String path, String ref) {
        String uri = "/repos/%s/contents/%s?ref=%s".formatted(repo, path, ref);
        return get(uri, RAW_MEDIA_TYPE, "%s 的文件 %s@%s".formatted(repo, path, ref));
    }

    /**
     * @param target 出错时写进异常 message 的人类可读目标描述（含仓库与路径，便于排查）
     */
    private String get(String uri, String acceptMediaType, String target) {
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
