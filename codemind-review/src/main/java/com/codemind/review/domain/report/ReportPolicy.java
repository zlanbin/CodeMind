package com.codemind.review.domain.report;

import com.codemind.review.domain.finding.Severity;

import java.util.Objects;

/** 行级门槛作用于合并组；maxInline=0 表示所有意见进入总结。 */
public record ReportPolicy(Severity minInlineSeverity, int maxInline) {
    public ReportPolicy {
        Objects.requireNonNull(minInlineSeverity, "minInlineSeverity");
        if (maxInline < 0) {
            throw new IllegalArgumentException("maxInline 不能为负");
        }
    }

    public static ReportPolicy defaults() {
        return new ReportPolicy(Severity.MAJOR, 10);
    }
}
