package com.codemind.review.application;

import com.codemind.review.domain.chunk.DiffFile;
import com.codemind.review.domain.finding.Finding;
import com.codemind.review.domain.report.ReportPolicy;
import com.codemind.review.domain.report.ReviewReport;
import com.codemind.review.domain.report.ReviewReportBuilder;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 可供后续真实发布前预览复用的固定输入与手算期望；本测试无网络或发布副作用。 */
class ReviewReportPreviewTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void deterministicInputProducesSavedReportAndMarkdown() throws IOException {
        PreviewInput input = mapper.readValue(resource("input.json"), PreviewInput.class);
        ReviewReport report = new ReviewReportBuilder().build(input.findings(), input.files(), input.degradations(), input.policy());
        assertThat(report).isEqualTo(mapper.readValue(resource("expected-report.json"), ReviewReport.class));
        ReviewMarkdownRenderer renderer = new ReviewMarkdownRenderer();
        assertThat(renderer.renderSummary(report)).isEqualTo(resource("summary.txt"));
        assertThat(report.inline()).hasSize(1);
        assertThat(renderer.renderInline(report.inline().getFirst())).isEqualTo(resource("inline-1.txt"));
    }

    private static String resource(String name) throws IOException {
        try (var stream = ReviewReportPreviewTest.class.getResourceAsStream("/report-preview/" + name)) {
            if (stream == null) {
                throw new IOException("Missing preview resource: " + name);
            }
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8).replace("\r\n", "\n");
        }
    }

    private record PreviewInput(List<Finding> findings, List<DiffFile> files, List<String> degradations,
                                ReportPolicy policy) {}
}
