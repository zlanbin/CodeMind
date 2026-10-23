package com.codemind.review.domain.report;

import com.codemind.review.domain.finding.Finding;

import java.util.Objects;

/** 未进入行级评论的原始意见及其原因。 */
public record SummaryItem(Finding finding, Reason reason) {
    public SummaryItem {
        Objects.requireNonNull(finding, "finding");
        Objects.requireNonNull(reason, "reason");
    }
}
