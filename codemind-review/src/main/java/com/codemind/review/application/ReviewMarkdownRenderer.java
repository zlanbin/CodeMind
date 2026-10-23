package com.codemind.review.application;

import com.codemind.common.event.FindingMarker;
import com.codemind.review.domain.finding.Finding;
import com.codemind.review.domain.finding.Severity;
import com.codemind.review.domain.report.InlineComment;
import com.codemind.review.domain.report.Reason;
import com.codemind.review.domain.report.ReviewReport;
import com.codemind.review.domain.report.SummaryItem;

/** 报告的 Markdown 展示；平台专用 task 标记由发布适配器追加。 */
public final class ReviewMarkdownRenderer {
    public String renderSummary(ReviewReport report) {
        int covered = report.inline().stream().mapToInt(comment -> comment.merged().size()).sum();
        StringBuilder markdown = new StringBuilder("## CodeMind 评审总结\n\n");
        markdown.append("- 原始意见总数 N：").append(report.totalFindings()).append('\n')
                .append("- 行级评论组数 K：").append(report.inline().size()).append('\n')
                .append("- 行级覆盖原始意见数 L：").append(covered).append('\n')
                .append("- 总结意见数 M：").append(report.summary().size()).append('\n')
                .append("- N = L + M：").append(report.totalFindings()).append(" = ")
                .append(covered).append(" + ").append(report.summary().size()).append("\n\n");

        if (!report.degradations().isEmpty()) {
            markdown.append("### 评审覆盖不完整\n\n");
            for (String degradation : report.degradations()) {
                markdown.append("- ").append(degradation).append('\n');
            }
            markdown.append('\n');
        } else if (report.totalFindings() == 0) {
            markdown.append("未发现问题。\n\n");
        }

        if (!report.summary().isEmpty()) {
            markdown.append("## 非行级意见\n\n");
            for (Severity severity : Severity.values()) {
                var items = report.summary().stream().filter(item -> item.finding().severity() == severity).toList();
                if (items.isEmpty()) {
                    continue;
                }
                markdown.append("### ").append(severity).append("\n\n");
                for (SummaryItem item : items) {
                    Finding finding = item.finding();
                    markdown.append("位置：").append(finding.file());
                    if (finding.line() > 0) {
                        markdown.append(':').append(finding.line());
                    }
                    markdown.append("\n\n转入总结原因：").append(reasonText(item.reason())).append("\n\n");
                    appendFinding(markdown, finding);
                }
            }
        }
        return markdown.toString();
    }

    public String renderInline(InlineComment comment) {
        StringBuilder markdown = new StringBuilder("### ").append(comment.maxSeverity())
                .append(" · 合并 ").append(comment.merged().size()).append(" 条意见\n\n");
        for (Finding finding : comment.merged()) {
            appendFinding(markdown, finding);
            // M9：每条 Finding 带隐藏标记，反馈回来时据此对回指纹与类别（ARCHITECTURE 7.2）
            if (FindingMarker.isFormattable(finding.fingerprint(), finding.category().name())) {
                markdown.append(FindingMarker.format(finding.fingerprint(), finding.category().name())).append("\n\n");
            }
        }
        return markdown.toString();
    }

    private static void appendFinding(StringBuilder markdown, Finding finding) {
        markdown.append("**维度：").append(finding.category()).append(" · 严重度：")
                .append(finding.severity()).append("**\n\n")
                .append("描述：").append(finding.message()).append("\n\n")
                .append("建议：").append(finding.suggestion().isBlank() ? "未提供" : finding.suggestion())
                .append("\n\n");
    }

    private static String reasonText(Reason reason) {
        return switch (reason) {
            case NO_LINE -> "无具体行号";
            case OUTSIDE_DIFF -> "不在 diff 新侧可评论范围";
            case BELOW_THRESHOLD -> "低于行级严重度门槛";
            case OVER_LIMIT -> "超过行级评论数量上限";
        };
    }
}
