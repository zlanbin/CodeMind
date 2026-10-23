package com.codemind.review.domain.report;

import com.codemind.review.domain.chunk.ChangeType;
import com.codemind.review.domain.chunk.DiffFile;
import com.codemind.review.domain.chunk.DiffHunk;
import com.codemind.review.domain.finding.Category;
import com.codemind.review.domain.finding.Finding;
import com.codemind.review.domain.finding.Severity;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReviewReportBuilderTest {
    private static final String INVENTORY = "src/Inventory.java";
    private final ReviewReportBuilder builder = new ReviewReportBuilder();

    @Test
    void lineZeroGoesToSummary() {
        Finding finding = finding("unknown.java", 0, Severity.BLOCKER, Category.DESIGN, "file level");
        ReviewReport report = build(List.of(finding));
        assertThat(report.inline()).isEmpty();
        assertThat(report.summary()).containsExactly(new SummaryItem(finding, Reason.NO_LINE));
        assertThat(report.totalFindings()).isEqualTo(1);
    }

    @Test
    void outsideHunkAndUnknownFileGoToSummary() {
        Finding before = finding(INVENTORY, 9, Severity.MAJOR, Category.CORRECTNESS, "before");
        Finding after = finding(INVENTORY, 13, Severity.MAJOR, Category.CORRECTNESS, "after");
        Finding gap = finding(INVENTORY, 15, Severity.MAJOR, Category.CORRECTNESS, "gap");
        Finding unknown = finding("unknown.java", 11, Severity.MAJOR, Category.CORRECTNESS, "unknown");
        ReviewReport report = builder.build(List.of(unknown, after, before, gap), List.of(
                new DiffFile(INVENTORY, INVENTORY, ChangeType.MODIFIED, List.of(hunk(),
                        new DiffHunk(20, 1, 20, 1, List.of("+later"))))), List.of(), ReportPolicy.defaults());
        assertThat(report.inline()).isEmpty();
        assertThat(report.summary()).containsExactly(
                new SummaryItem(before, Reason.OUTSIDE_DIFF), new SummaryItem(after, Reason.OUTSIDE_DIFF),
                new SummaryItem(gap, Reason.OUTSIDE_DIFF), new SummaryItem(unknown, Reason.OUTSIDE_DIFF));
    }

    @Test
    void renamedUsesNewPath() {
        Finding old = finding("src/Old.java", 11, Severity.MAJOR, Category.CORRECTNESS, "old");
        Finding current = finding(INVENTORY, 11, Severity.MAJOR, Category.CORRECTNESS, "current");
        ReviewReport report = builder.build(List.of(old, current),
                List.of(new DiffFile("src/Old.java", INVENTORY, ChangeType.RENAMED, List.of(hunk()))),
                List.of(), ReportPolicy.defaults());
        assertThat(report.inline()).containsExactly(new InlineComment(INVENTORY, 11, Severity.MAJOR, List.of(current)));
        assertThat(report.summary()).containsExactly(new SummaryItem(old, Reason.OUTSIDE_DIFF));
    }

    @Test
    void deletedFileNeverUsesRightSide() {
        Finding finding = finding(INVENTORY, 11, Severity.BLOCKER, Category.CORRECTNESS, "deleted");
        // 即使上游给了看似有效的新侧区间，DELETED 也必须被显式排除。
        ReviewReport report = builder.build(List.of(finding),
                List.of(new DiffFile(INVENTORY, INVENTORY, ChangeType.DELETED, List.of(hunk()))),
                List.of(), ReportPolicy.defaults());
        assertThat(report.inline()).isEmpty();
        assertThat(report.summary()).containsExactly(new SummaryItem(finding, Reason.OUTSIDE_DIFF));
    }

    @Test
    void contextLineCanBeInline() {
        Finding first = finding(INVENTORY, 10, Severity.MAJOR, Category.CORRECTNESS, "context first");
        Finding last = finding(INVENTORY, 12, Severity.MAJOR, Category.CORRECTNESS, "context last");
        ReviewReport report = build(List.of(last, first));
        assertThat(report.summary()).isEmpty();
        assertThat(report.inline()).containsExactly(
                new InlineComment(INVENTORY, 10, Severity.MAJOR, List.of(first)),
                new InlineComment(INVENTORY, 12, Severity.MAJOR, List.of(last)));
    }

    @Test
    void pureDeletionAndHunklessFilesCannotBeInline() {
        Finding finding = finding(INVENTORY, 10, Severity.MAJOR, Category.CORRECTNESS, "no new lines");
        for (List<DiffHunk> hunks : List.of(List.<DiffHunk>of(),
                List.of(new DiffHunk(10, 1, 10, 0, List.of("-gone"))))) {
            ReviewReport report = builder.build(List.of(finding),
                    List.of(new DiffFile(INVENTORY, INVENTORY, ChangeType.MODIFIED, hunks)),
                    List.of(), ReportPolicy.defaults());
            assertThat(report.inline()).isEmpty();
            assertThat(report.summary()).containsExactly(new SummaryItem(finding, Reason.OUTSIDE_DIFF));
        }
    }

    @Test
    void mergesThreeDimensionsAtInventoryLine11() {
        Finding correctness = finding(INVENTORY, 11, Severity.CRITICAL, Category.CORRECTNESS, "off by one");
        Finding security = finding(INVENTORY, 11, Severity.MAJOR, Category.SECURITY, "untrusted index");
        Finding test = finding(INVENTORY, 11, Severity.MAJOR, Category.TEST, "missing boundary test");
        ReviewReport report = build(List.of(test, security, correctness));
        assertThat(report.inline()).containsExactly(new InlineComment(INVENTORY, 11, Severity.CRITICAL,
                List.of(correctness, security, test)));
        assertThat(report.inline().getFirst().merged()).hasSize(3);
        assertThat(report.summary()).isEmpty();
        assertThat(report.totalFindings()).isEqualTo(3);
    }

    @Test
    void mixedMajorAndMinorStaysOneInlineGroup() {
        Finding major = finding(INVENTORY, 11, Severity.MAJOR, Category.CORRECTNESS, "major");
        Finding minor = finding(INVENTORY, 11, Severity.MINOR, Category.STYLE, "minor");
        ReviewReport report = build(List.of(minor, major));
        assertThat(report.inline()).containsExactly(new InlineComment(INVENTORY, 11, Severity.MAJOR, List.of(major, minor)));
        assertThat(report.summary()).isEmpty();
    }

    @Test
    void belowThresholdRetainsEveryOriginalFinding() {
        Finding minor = finding(INVENTORY, 11, Severity.MINOR, Category.STYLE, "minor");
        Finding info = finding(INVENTORY, 11, Severity.INFO, Category.TEST, "info");
        ReviewReport report = build(List.of(info, minor));
        assertThat(report.inline()).isEmpty();
        assertThat(report.summary()).containsExactly(
                new SummaryItem(minor, Reason.BELOW_THRESHOLD), new SummaryItem(info, Reason.BELOW_THRESHOLD));
    }

    @Test
    void ordersAndCapsAtTen() {
        List<Finding> findings = new ArrayList<>();
        findings.add(finding("z.java", 10, Severity.BLOCKER, Category.CORRECTNESS, "blocker"));
        findings.add(finding("b.java", 11, Severity.CRITICAL, Category.TEST, "critical second"));
        findings.add(finding("b.java", 11, Severity.MINOR, Category.STYLE, "critical companion"));
        findings.add(finding("b.java", 10, Severity.CRITICAL, Category.CORRECTNESS, "critical first"));
        findings.add(finding("b.java", 10, Severity.INFO, Category.STYLE, "critical companion"));
        findings.add(finding("a.java", 10, Severity.CRITICAL, Category.TEST, "single"));
        for (int line = 10; line <= 17; line++) {
            findings.add(finding("c.java", line, Severity.MAJOR, Category.CORRECTNESS, "major " + line));
        }
        findings.add(finding("c.java", 17, Severity.MINOR, Category.STYLE, "over limit companion"));
        // 该组有两条，故在单条 MAJOR 组前，不能按逐条意见截断。
        ReviewReport report = builder.build(findings, List.of(wideFile("a.java"), wideFile("b.java"),
                wideFile("c.java"), wideFile("z.java")), List.of(), ReportPolicy.defaults());
        assertThat(report.inline()).extracting(c -> c.path() + ":" + c.line()).containsExactly(
                "z.java:10", "b.java:10", "b.java:11", "a.java:10", "c.java:17",
                "c.java:10", "c.java:11", "c.java:12", "c.java:13", "c.java:14");
        assertThat(report.summary()).containsExactly(
                new SummaryItem(findings.get(11), Reason.OVER_LIMIT),
                new SummaryItem(findings.get(12), Reason.OVER_LIMIT));
        assertThat(report.totalFindings()).isEqualTo(15);
    }

    @Test
    void overLimitKeepsAllMembersOfExcludedGroup() {
        Finding blocker = finding(INVENTORY, 10, Severity.BLOCKER, Category.CORRECTNESS, "blocker");
        Finding major = finding(INVENTORY, 11, Severity.MAJOR, Category.CORRECTNESS, "major");
        Finding minor = finding(INVENTORY, 11, Severity.MINOR, Category.STYLE, "minor");
        ReviewReport report = builder.build(List.of(minor, major, blocker), List.of(inventory()), List.of(),
                new ReportPolicy(Severity.MAJOR, 1));
        assertThat(report.inline()).containsExactly(new InlineComment(INVENTORY, 10, Severity.BLOCKER, List.of(blocker)));
        assertThat(report.summary()).containsExactly(
                new SummaryItem(major, Reason.OVER_LIMIT), new SummaryItem(minor, Reason.OVER_LIMIT));
    }

    @Test
    void stableKeysOrderMergedFindingsAndSummary() {
        // 每个相邻值只在下一排序键上有区别：severity/category/message/suggestion/fingerprint。
        Finding severe = explicit(11, Severity.BLOCKER, Category.TEST, "z", "z", "z");
        Finding category = explicit(11, Severity.MAJOR, Category.CORRECTNESS, "z", "z", "z");
        Finding message = explicit(11, Severity.MAJOR, Category.STYLE, "a", "z", "z");
        Finding suggestion = explicit(11, Severity.MAJOR, Category.STYLE, "b", "a", "z");
        Finding fingerprintA = explicit(11, Severity.MAJOR, Category.STYLE, "b", "b", "a");
        Finding fingerprintB = explicit(11, Severity.MAJOR, Category.STYLE, "b", "b", "b");
        List<Finding> expected = List.of(severe, category, message, suggestion, fingerprintA, fingerprintB);
        List<Finding> reversed = new ArrayList<>(expected);
        Collections.reverse(reversed);
        assertThat(build(reversed).inline().getFirst().merged()).containsExactlyElementsOf(expected);
        ReviewReport summary = builder.build(reversed, List.of(inventory()), List.of(), new ReportPolicy(Severity.INFO, 0));
        assertThat(summary.summary()).extracting(SummaryItem::finding).containsExactlyElementsOf(expected);
    }

    @Test
    void shuffledInputProducesEqualReport() {
        List<Finding> original = List.of(
                finding(INVENTORY, 11, Severity.CRITICAL, Category.SECURITY, "security"),
                finding(INVENTORY, 11, Severity.MINOR, Category.STYLE, "style"),
                finding(INVENTORY, 10, Severity.MAJOR, Category.TEST, "test"),
                finding(INVENTORY, 0, Severity.INFO, Category.DESIGN, "file"),
                finding("unknown.java", 11, Severity.MAJOR, Category.CORRECTNESS, "unknown"),
                finding(INVENTORY, 12, Severity.MAJOR, Category.CORRECTNESS, "last"));
        List<DiffFile> files = List.of(inventory(), wideFile("other.java"));
        ReviewReport expected = builder.build(original, files, List.of("z降级", "a降级"), new ReportPolicy(Severity.MAJOR, 1));
        for (int seed = 0; seed < 30; seed++) {
            List<Finding> shuffled = new ArrayList<>(original);
            Collections.shuffle(shuffled, new Random(seed));
            List<DiffFile> shuffledFiles = new ArrayList<>(files);
            Collections.shuffle(shuffledFiles, new Random(seed));
            assertThat(builder.build(shuffled, shuffledFiles, List.of("a降级", "z降级"), new ReportPolicy(Severity.MAJOR, 1)))
                    .isEqualTo(expected);
        }
        assertThat(expected.degradations()).containsExactly("a降级", "z降级");
    }

    @Test
    void zeroLimitMovesAllToSummary() {
        Finding major = finding(INVENTORY, 11, Severity.MAJOR, Category.CORRECTNESS, "major");
        Finding minor = finding(INVENTORY, 12, Severity.MINOR, Category.STYLE, "minor");
        ReviewReport report = builder.build(List.of(minor, major), List.of(inventory()), List.of(), new ReportPolicy(Severity.MAJOR, 0));
        assertThat(report.inline()).isEmpty();
        assertThat(report.summary()).containsExactly(new SummaryItem(major, Reason.OVER_LIMIT),
                new SummaryItem(minor, Reason.BELOW_THRESHOLD));
    }

    @Test
    void configurableThresholdUsesBlockerToInfoSeverityOrder() {
        List<Finding> findings = List.of(
                finding(INVENTORY, 10, Severity.BLOCKER, Category.CORRECTNESS, "blocker"),
                finding(INVENTORY, 11, Severity.CRITICAL, Category.SECURITY, "critical"),
                finding(INVENTORY, 12, Severity.INFO, Category.STYLE, "info"));
        ReviewReport strict = builder.build(findings, List.of(inventory()), List.of(), new ReportPolicy(Severity.BLOCKER, 10));
        assertThat(strict.inline()).extracting(InlineComment::maxSeverity).containsExactly(Severity.BLOCKER);
        assertThat(strict.summary()).extracting(SummaryItem::reason).containsExactly(Reason.BELOW_THRESHOLD, Reason.BELOW_THRESHOLD);
        ReviewReport relaxed = builder.build(findings, List.of(inventory()), List.of(), new ReportPolicy(Severity.INFO, 10));
        assertThat(relaxed.inline()).extracting(InlineComment::maxSeverity)
                .containsExactly(Severity.BLOCKER, Severity.CRITICAL, Severity.INFO);
        assertThat(relaxed.summary()).isEmpty();
    }

    @Test
    void defaultsLimitTenAndRejectsNegativeLimit() {
        assertThat(ReportPolicy.defaults()).isEqualTo(new ReportPolicy(Severity.MAJOR, 10));
        assertThatThrownBy(() -> new ReportPolicy(Severity.MAJOR, -1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ReportPolicy(null, 10)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void reportCollectionsAreImmutable() {
        Finding finding = finding(INVENTORY, 11, Severity.MAJOR, Category.CORRECTNESS, "major");
        List<Finding> merged = new ArrayList<>(List.of(finding));
        InlineComment comment = new InlineComment(INVENTORY, 11, Severity.MAJOR, merged);
        List<InlineComment> inline = new ArrayList<>(List.of(comment));
        List<SummaryItem> summary = new ArrayList<>(List.of(new SummaryItem(finding, Reason.OVER_LIMIT)));
        List<String> degradations = new ArrayList<>(List.of("降级"));
        ReviewReport report = new ReviewReport(inline, summary, degradations, 2);
        merged.clear(); inline.clear(); summary.clear(); degradations.clear();
        assertThat(report.inline()).containsExactly(comment);
        assertThat(comment.merged()).containsExactly(finding);
        assertThat(report.summary()).hasSize(1);
        assertThat(report.degradations()).containsExactly("降级");
        assertThatThrownBy(() -> report.inline().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> comment.merged().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> report.summary().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> report.degradations().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void builderDoesNotMutateInputsOrRetainMutableLists() {
        List<Finding> findings = new ArrayList<>(List.of(
                finding(INVENTORY, 11, Severity.MAJOR, Category.CORRECTNESS, "major")));
        List<String> degradations = new ArrayList<>(List.of("z", "a"));
        ReviewReport report = builder.build(findings, List.of(inventory()), degradations, ReportPolicy.defaults());
        assertThat(degradations).containsExactly("z", "a");
        findings.clear(); degradations.clear();
        assertThat(report.inline().getFirst().merged()).hasSize(1);
        assertThat(report.degradations()).containsExactly("a", "z");
    }

    private ReviewReport build(List<Finding> findings) {
        return builder.build(findings, List.of(inventory()), List.of(), ReportPolicy.defaults());
    }

    private static Finding explicit(int line, Severity severity, Category category, String message, String suggestion, String fp) {
        return new Finding(INVENTORY, line, severity, category, message, suggestion, fp);
    }

    private static Finding finding(String path, int line, Severity severity, Category category, String message) {
        return new Finding(path, line, severity, category, message, "fix " + message, path + ":" + line + ":" + message);
    }

    private static DiffHunk hunk() {
        return new DiffHunk(10, 3, 10, 3, List.of(" context", "-old", "+changed", " context"));
    }

    private static DiffFile inventory() {
        return new DiffFile(INVENTORY, INVENTORY, ChangeType.MODIFIED, List.of(hunk()));
    }

    private static DiffFile wideFile(String path) {
        return new DiffFile(path, path, ChangeType.MODIFIED,
                List.of(new DiffHunk(10, 8, 10, 8, List.of(" context"))));
    }
}
