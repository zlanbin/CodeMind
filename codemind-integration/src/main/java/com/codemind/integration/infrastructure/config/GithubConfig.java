package com.codemind.integration.infrastructure.config;

import com.codemind.integration.infrastructure.github.GithubScmClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.convert.DurationStyle;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.ClientHttpRequestFactorySettings;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

import java.time.Duration;

/**
 * GitHub 读客户端装配（M7a）。
 *
 * <p>超时设在这里而不在 {@link GithubScmClient} 构造器里：客户端若自己设 requestFactory，
 * 会把测试用 {@code MockRestServiceServer.bindTo(builder)} 装的 factory 顶掉。
 */
@Configuration
public class GithubConfig {

    /**
     * @param baseUrl GitHub API 根地址，私有部署（GitHub Enterprise）可覆盖
     * @param token   访问令牌，来自环境变量 GITHUB_TOKEN，不落仓库；空串为匿名
     * @param timeout 连接与读取超时
     */
    @Bean
    public GithubScmClient githubScmClient(
            @Value("${codemind.scm.github-api-base-url}") String baseUrl,
            @Value("${codemind.scm.github-token:}") String token,
            @Value("${codemind.scm.request-timeout:20s}") String timeout) {
        Duration duration = DurationStyle.detectAndParse(timeout);
        ClientHttpRequestFactorySettings settings = ClientHttpRequestFactorySettings.defaults()
                .withConnectTimeout(duration)
                .withReadTimeout(duration);
        RestClient.Builder builder = RestClient.builder()
                .requestFactory(ClientHttpRequestFactoryBuilder.detect().build(settings));
        return new GithubScmClient(builder, baseUrl, token);
    }
}
