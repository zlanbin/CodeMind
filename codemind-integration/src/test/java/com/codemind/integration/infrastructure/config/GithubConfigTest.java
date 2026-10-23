package com.codemind.integration.infrastructure.config;

import com.codemind.integration.application.port.ScmClient;
import com.codemind.integration.application.port.ScmReviewPublisher;
import com.codemind.integration.infrastructure.github.GithubScmClient;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

/** No Boot app or middleware: tests the configured bean's discoverability by both ports. */
class GithubConfigTest {

    @Test
    void bothPortsResolveToOneGithubClient() {
        new ApplicationContextRunner()
                .withUserConfiguration(GithubConfig.class, PortConsumers.class)
                .withPropertyValues("codemind.scm.github-api-base-url=https://api.github.test",
                        "codemind.scm.github-token=", "codemind.scm.request-timeout=2s")
                .run(context -> {
                    assertThat(context).hasNotFailed()
                            .hasSingleBean(ScmClient.class)
                            .hasSingleBean(ScmReviewPublisher.class)
                            .hasSingleBean(GithubScmClient.class);
                    GithubScmClient concrete = context.getBean(GithubScmClient.class);
                    Ports ports = context.getBean(Ports.class);
                    assertThat(ports.reader()).isSameAs(concrete);
                    assertThat(ports.publisher()).isSameAs(concrete);
                });
    }

    @Configuration(proxyBeanMethods = false)
    static class PortConsumers {
        @Bean
        Ports ports(ScmClient reader, ScmReviewPublisher publisher) {
            return new Ports(reader, publisher);
        }
    }

    record Ports(ScmClient reader, ScmReviewPublisher publisher) {}
}
