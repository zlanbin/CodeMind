package com.codemind.review.infrastructure.config;

import com.codemind.review.application.ReviewMarkdownRenderer;
import com.codemind.review.domain.chunk.ChangeType;
import com.codemind.review.domain.chunk.DiffFile;
import com.codemind.review.domain.chunk.DiffHunk;
import com.codemind.review.domain.finding.Category;
import com.codemind.review.domain.finding.Finding;
import com.codemind.review.domain.finding.Severity;
import com.codemind.review.domain.report.ReportPolicy;
import com.codemind.review.domain.report.ReviewReportBuilder;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.MapPropertySource;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ReviewReportConfigTest {
    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withUserConfiguration(ReviewReportConfig.class);

    @Test
    void defaultsAssembleUsablePolicyBuilderAndRendererWithoutExternalServices() {
        context.run(ctx -> {
            assertThat(ctx).hasSingleBean(ReportPolicy.class).hasSingleBean(ReviewReportBuilder.class)
                    .hasSingleBean(ReviewMarkdownRenderer.class);
            ReportPolicy policy = ctx.getBean(ReportPolicy.class);
            assertThat(policy).isEqualTo(new ReportPolicy(Severity.MAJOR, 10));
            assertThat(ctx.getBean(ReviewMarkdownRenderer.class).renderSummary(ctx.getBean(ReviewReportBuilder.class)
                    .build(List.of(), List.of(), List.of(), policy))).contains("未发现问题");
        });
    }

    @Test
    void configuredThresholdAndZeroLimitDriveBuilderBehavior() {
        context.withPropertyValues("codemind.report.min-inline-severity=INFO", "codemind.report.max-inline=0")
                .run(ctx -> {
                    ReportPolicy policy = ctx.getBean(ReportPolicy.class);
                    assertThat(policy).isEqualTo(new ReportPolicy(Severity.INFO, 0));
                    var report = ctx.getBean(ReviewReportBuilder.class).build(List.of(finding()), List.of(diff()), List.of(), policy);
                    assertThat(report.inline()).isEmpty();
                    assertThat(report.summary()).extracting(item -> item.reason().name()).containsExactly("OVER_LIMIT");
                });
    }

    @Test
    void negativeConfiguredLimitFailsFast() {
        context.withPropertyValues("codemind.report.max-inline=-1").run(ctx -> assertThat(ctx).hasFailed());
    }

    @Test
    void applicationYamlMapsEnvironmentOverridesToPolicy() {
        context.withInitializer(applicationContext -> {
            // 仅模拟非秘密环境变量；不加载完整应用或任何中间件自动配置。
            applicationContext.getEnvironment().getPropertySources().addFirst(new MapPropertySource("report-test-env",
                    Map.of("CODEMIND_MIN_INLINE_SEVERITY", "CRITICAL", "CODEMIND_MAX_INLINE", "3")));
            new ConfigDataApplicationContextInitializer().initialize(applicationContext);
        }).run(ctx -> assertThat(ctx.getBean(ReportPolicy.class)).isEqualTo(new ReportPolicy(Severity.CRITICAL, 3)));
    }

    private static Finding finding() {
        return new Finding("a.java", 1, Severity.INFO, Category.STYLE, "style", "", "fp");
    }

    private static DiffFile diff() {
        return new DiffFile("a.java", "a.java", ChangeType.MODIFIED,
                List.of(new DiffHunk(1, 1, 1, 1, List.of("+line"))));
    }
}
