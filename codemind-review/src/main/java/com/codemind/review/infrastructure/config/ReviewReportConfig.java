package com.codemind.review.infrastructure.config;

import com.codemind.review.application.ReviewMarkdownRenderer;
import com.codemind.review.domain.finding.Severity;
import com.codemind.review.domain.report.ReportPolicy;
import com.codemind.review.domain.report.ReviewReportBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 报告规则与展示的装配；领域对象不承担配置绑定。 */
@Configuration
public class ReviewReportConfig {
    @Bean
    public ReportPolicy reportPolicy(
            @Value("${codemind.report.min-inline-severity:MAJOR}") Severity minInlineSeverity,
            @Value("${codemind.report.max-inline:10}") int maxInline) {
        return new ReportPolicy(minInlineSeverity, maxInline);
    }

    @Bean
    public ReviewReportBuilder reviewReportBuilder() {
        return new ReviewReportBuilder();
    }

    @Bean
    public ReviewMarkdownRenderer reviewMarkdownRenderer() {
        return new ReviewMarkdownRenderer();
    }
}
