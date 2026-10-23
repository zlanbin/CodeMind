package com.codemind.review.domain.report;

import java.util.List;
import java.util.Objects;

/** 发布前的内存报告；持久化边界保存渲染后的请求而非本领域对象。 */
public record ReviewReport(List<InlineComment> inline, List<SummaryItem> summary,
                           List<String> degradations, int totalFindings) {
    public ReviewReport {
        inline = List.copyOf(Objects.requireNonNull(inline, "inline"));
        summary = List.copyOf(Objects.requireNonNull(summary, "summary"));
        degradations = List.copyOf(Objects.requireNonNull(degradations, "degradations"));
    }
}
