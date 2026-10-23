package com.codemind.review.application;

import com.codemind.common.event.FindingFeedbackReceived.FindingRef;
import com.codemind.common.event.FindingMarker;
import com.codemind.review.domain.finding.Category;
import com.codemind.review.domain.finding.Finding;
import com.codemind.review.domain.finding.Severity;
import com.codemind.review.domain.report.InlineComment;
import com.codemind.review.domain.report.Reason;
import com.codemind.review.domain.report.ReviewReport;
import com.codemind.review.domain.report.SummaryItem;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ReviewMarkdownRendererTest {
    private final ReviewMarkdownRenderer renderer = new ReviewMarkdownRenderer();

    @Test
    void statisticsDistinguishInlineGroupsFromOriginalFindings() {
        Finding correctness = finding(11, Severity.CRITICAL, Category.CORRECTNESS, "边界越界", "改用 < size()");
        Finding security = finding(11, Severity.MAJOR, Category.SECURITY, "输入未校验", "校验输入");
        Finding test = finding(11, Severity.MINOR, Category.TEST, "缺少边界测试", "增加空集合用例");
        Finding file = finding(0, Severity.INFO, Category.DESIGN, "文件级说明", "补充设计说明");
        String markdown = renderer.renderSummary(new ReviewReport(
                List.of(new InlineComment("src/Inventory.java", 11, Severity.CRITICAL, List.of(correctness, security, test))),
                List.of(new SummaryItem(file, Reason.NO_LINE)), List.of(), 4));
        assertThat(markdown).contains("原始意见总数 N：4", "行级评论组数 K：1", "行级覆盖原始意见数 L：3", "总结意见数 M：1", "N = L + M：4 = 3 + 1");
        assertThat(markdown).doesNotContain("codemind:task=", "所有检查均通过");
    }

    @Test
    void rendersAllFourReasonsInChinese() {
        List<SummaryItem> items = List.of(
                new SummaryItem(finding(0, Severity.MAJOR, Category.DESIGN, "文件级", "建议文件级"), Reason.NO_LINE),
                new SummaryItem(finding(99, Severity.MAJOR, Category.CORRECTNESS, "区间外", "建议区间外"), Reason.OUTSIDE_DIFF),
                new SummaryItem(finding(12, Severity.MINOR, Category.STYLE, "轻微", "建议轻微"), Reason.BELOW_THRESHOLD),
                new SummaryItem(finding(13, Severity.MAJOR, Category.TEST, "超限", "建议超限"), Reason.OVER_LIMIT));
        String markdown = renderer.renderSummary(new ReviewReport(List.of(), items, List.of(), 4));
        assertThat(markdown).contains("无具体行号", "不在 diff 新侧可评论范围", "低于行级严重度门槛", "超过行级评论数量上限",
                "文件级", "区间外", "轻微", "超限", "建议文件级", "建议区间外", "建议轻微", "建议超限", "src/Inventory.java:99");
        assertThat(markdown).doesNotContain("codemind:task=");
    }

    @Test
    void summaryGroupsNonInlineFindingsBySeverity() {
        Finding minor = finding(12, Severity.MINOR, Category.STYLE, "minor", "fix minor");
        Finding major = finding(0, Severity.MAJOR, Category.DESIGN, "major", "fix major");
        Finding critical = finding(99, Severity.CRITICAL, Category.SECURITY, "critical", "fix critical");
        String markdown = renderer.renderSummary(new ReviewReport(List.of(), List.of(
                new SummaryItem(critical, Reason.OUTSIDE_DIFF), new SummaryItem(major, Reason.NO_LINE),
                new SummaryItem(minor, Reason.BELOW_THRESHOLD)), List.of(), 3));
        assertThat(markdown).containsSubsequence("### CRITICAL", "critical", "### MAJOR", "major", "### MINOR", "minor");
    }

    @Test
    void inlineShowsHighestSeverityAndEveryDimensionDescriptionAndSuggestion() {
        Finding correctness = finding(11, Severity.CRITICAL, Category.CORRECTNESS, "边界越界", "改用 < size()");
        Finding security = finding(11, Severity.MAJOR, Category.SECURITY, "输入未校验", "校验输入");
        Finding test = finding(11, Severity.MINOR, Category.TEST, "缺少边界测试", "增加空集合用例");
        String markdown = renderer.renderInline(new InlineComment("src/Inventory.java", 11, Severity.CRITICAL,
                List.of(correctness, security, test)));
        assertThat(markdown).startsWith("### CRITICAL");
        assertThat(markdown).contains("CORRECTNESS", "CRITICAL", "边界越界", "改用 < size()",
                "SECURITY", "MAJOR", "输入未校验", "校验输入", "TEST", "MINOR", "缺少边界测试", "增加空集合用例");
        assertThat(markdown).doesNotContain("codemind:task=");
    }

    @Test
    void inlineCarriesOneHiddenMarkerPerRealFinding() {
        Finding real = Finding.of("src/Inventory.java", 11, Severity.MAJOR, Category.CORRECTNESS, "越界", "改 <",
                "for (i = 0; i <= size(); i++)");
        Finding second = Finding.of("src/Inventory.java", 11, Severity.MINOR, Category.TEST, "缺测试", "补用例", "x");
        Finding legacy = finding(11, Severity.MINOR, Category.STYLE, "无合法指纹", "");

        String markdown = renderer.renderInline(new InlineComment("src/Inventory.java", 11, Severity.MAJOR,
                List.of(real, second, legacy)));

        assertThat(FindingMarker.parseAll(markdown)).containsExactly(
                new FindingRef(real.fingerprint(), "CORRECTNESS"), new FindingRef(second.fingerprint(), "TEST"));
        assertThat(FindingMarker.strip(markdown)).doesNotContain("codemind:finding").contains("越界", "无合法指纹");
    }

    @Test
    void emptyReportWithoutDegradationSaysNoFindings() {
        String markdown = renderer.renderSummary(new ReviewReport(List.of(), List.of(), List.of(), 0));
        assertThat(markdown).contains("未发现问题", "原始意见总数 N：0", "行级评论组数 K：0", "N = L + M：0 = 0 + 0");
        assertThat(markdown).doesNotContain("评审覆盖不完整", "所有检查均通过");
    }

    @Test
    void degradationDoesNotClaimCompleteReviewEvenWithoutFindings() {
        String markdown = renderer.renderSummary(new ReviewReport(List.of(), List.of(),
                List.of("SECURITY 超时", "TEST 缺失上下文"), 0));
        assertThat(markdown).contains("评审覆盖不完整", "SECURITY 超时", "TEST 缺失上下文");
        assertThat(markdown).doesNotContain("未发现问题", "所有检查均通过");
    }

    @Test
    void degradationIsShownAlongsideFindings() {
        Finding finding = finding(0, Severity.MAJOR, Category.DESIGN, "待处理", "补充检查");
        String markdown = renderer.renderSummary(new ReviewReport(List.of(),
                List.of(new SummaryItem(finding, Reason.NO_LINE)), List.of("PERFORMANCE 超时"), 1));
        assertThat(markdown).contains("待处理", "PERFORMANCE 超时", "评审覆盖不完整");
        assertThat(markdown).doesNotContain("未发现问题");
    }

    @Test
    void emptySuggestionIsRenderedExplicitly() {
        Finding finding = finding(11, Severity.MAJOR, Category.CORRECTNESS, "问题仍须显示", "");
        assertThat(renderer.renderInline(new InlineComment("src/Inventory.java", 11, Severity.MAJOR, List.of(finding))))
                .contains("问题仍须显示", "建议：未提供");
    }

    private static Finding finding(int line, Severity severity, Category category, String message, String suggestion) {
        return new Finding("src/Inventory.java", line, severity, category, message, suggestion, message);
    }
}
