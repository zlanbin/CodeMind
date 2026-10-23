package com.codemind.review.domain.report;

import com.codemind.review.domain.finding.Finding;
import com.codemind.review.domain.finding.Severity;

import java.util.List;
import java.util.Objects;

/** 同一新侧位置合并的评论，保留全部原始意见。 */
public record InlineComment(String path, int line, Severity maxSeverity, List<Finding> merged) {
    public InlineComment {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(maxSeverity, "maxSeverity");
        merged = List.copyOf(Objects.requireNonNull(merged, "merged"));
    }
}
