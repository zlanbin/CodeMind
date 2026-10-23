package com.codemind.review.domain.report;

import com.codemind.review.domain.chunk.ChangeType;
import com.codemind.review.domain.chunk.DiffFile;
import com.codemind.review.domain.chunk.DiffHunk;
import com.codemind.review.domain.finding.Finding;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** 新侧校验 → 同行合并 → 组门槛 → 排序限额，完全不依赖平台和基础设施。 */
public final class ReviewReportBuilder {
    // Severity 的自然顺序是 BLOCKER → INFO，即越小越严重；category 按名字排序。
    private static final Comparator<Finding> FINDING_ORDER = Comparator.comparing(Finding::severity)
            .thenComparing(finding -> finding.category().name())
            .thenComparing(Finding::file)
            .thenComparingInt(Finding::line)
            .thenComparing(Finding::message)
            .thenComparing(Finding::suggestion)
            .thenComparing(Finding::fingerprint);

    private static final Comparator<InlineComment> INLINE_ORDER = Comparator.comparing(InlineComment::maxSeverity)
            .thenComparing(Comparator.comparingInt((InlineComment comment) -> comment.merged().size()).reversed())
            .thenComparing(InlineComment::path)
            .thenComparingInt(InlineComment::line);

    public ReviewReport build(List<Finding> findings, List<DiffFile> files,
                              List<String> degradations, ReportPolicy policy) {
        Map<String, List<DiffHunk>> newSide = new HashMap<>();
        for (DiffFile file : files) {
            if (file.changeType() != ChangeType.DELETED) {
                newSide.computeIfAbsent(file.path(), ignored -> new ArrayList<>()).addAll(file.hunks());
            }
        }

        List<SummaryItem> summary = new ArrayList<>();
        Map<Location, List<Finding>> groups = new HashMap<>();
        for (Finding finding : findings) {
            if (finding.line() == 0) {
                summary.add(new SummaryItem(finding, Reason.NO_LINE));
            } else if (newSide.getOrDefault(finding.file(), List.of()).stream()
                    .noneMatch(hunk -> hunk.coversNewLine(finding.line()))) {
                summary.add(new SummaryItem(finding, Reason.OUTSIDE_DIFF));
            } else {
                groups.computeIfAbsent(new Location(finding.file(), finding.line()), ignored -> new ArrayList<>())
                        .add(finding);
            }
        }

        List<InlineComment> candidates = new ArrayList<>();
        groups.forEach((location, merged) -> {
            merged.sort(FINDING_ORDER);
            var maxSeverity = merged.getFirst().severity();
            if (maxSeverity.compareTo(policy.minInlineSeverity()) > 0) {
                merged.forEach(finding -> summary.add(new SummaryItem(finding, Reason.BELOW_THRESHOLD)));
            } else {
                candidates.add(new InlineComment(location.path(), location.line(), maxSeverity, merged));
            }
        });
        candidates.sort(INLINE_ORDER);
        int limit = Math.min(policy.maxInline(), candidates.size());
        for (InlineComment excluded : candidates.subList(limit, candidates.size())) {
            excluded.merged().forEach(finding -> summary.add(new SummaryItem(finding, Reason.OVER_LIMIT)));
        }
        summary.sort(Comparator.comparing(SummaryItem::finding, FINDING_ORDER));
        return new ReviewReport(candidates.subList(0, limit), summary,
                degradations.stream().sorted().toList(), findings.size());
    }

    private record Location(String path, int line) {}
}
