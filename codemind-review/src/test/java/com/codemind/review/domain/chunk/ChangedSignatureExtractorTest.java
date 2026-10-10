package com.codemind.review.domain.chunk;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ChangedSignatureExtractor} 单测（M6 设计审查检查清单：单测验证 BuildChangeMapNode 能提取方法签名变更）。
 *
 * <p>重点是 {@link SignatureChange.Kind#SIGNATURE_CHANGED}——M6 验证点「跨文件签名不一致」
 * 要靠它区分 {@code foo(int)} 和 {@code foo(String)}。
 */
class ChangedSignatureExtractorTest {

    private final DiffParser parser = new DiffParser();
    private final ChangedSignatureExtractor extractor = new ChangedSignatureExtractor();

    private static Map<String, String> crossSignatureSources() {
        return Map.of(
                "PaymentGateway.java", DiffResources.source("PaymentGateway.java"),
                "RefundClient.java", DiffResources.source("RefundClient.java"),
                "AuditLogger.java", DiffResources.source("AuditLogger.java"),
                "NotificationSender.java", DiffResources.source("NotificationSender.java"));
    }

    @Test
    void detectsParameterTypeChangeWithFullSignatures() {
        List<DiffFile> files = parser.parse(DiffResources.diff("cross-signature.diff"));

        List<SignatureChange> changes = extractor.extract(files, crossSignatureSources());

        SignatureChange refund = changes.stream()
                .filter(c -> c.file().equals("RefundClient.java"))
                .filter(c -> c.kind() == SignatureChange.Kind.SIGNATURE_CHANGED)
                .findFirst()
                .orElseThrow(() -> new AssertionError("未检出 RefundClient 的签名变更，实际: " + changes));

        assertThat(refund.signature()).isEqualTo("RefundClient#submitRefund(long, int)");
        assertThat(refund.previous()).isEqualTo("RefundClient#submitRefund(String, int)");
        assertThat(refund.describe()).contains("String, int").contains("long, int");
    }

    @Test
    void bodyOnlyChangeIsNotReportedAsSignatureChange() {
        List<DiffFile> files = parser.parse(DiffResources.diff("cross-signature.diff"));

        List<SignatureChange> changes = extractor.extract(files, crossSignatureSources());

        // AuditLogger#record 只改了阈值，签名没动
        assertThat(changes)
                .filteredOn(c -> c.file().equals("AuditLogger.java"))
                .isNotEmpty()
                .allSatisfy(c -> assertThat(c.kind()).isNotEqualTo(SignatureChange.Kind.SIGNATURE_CHANGED));
        assertThat(changes)
                .filteredOn(c -> c.kind() == SignatureChange.Kind.SIGNATURE_CHANGED)
                .hasSize(1);
    }

    @Test
    void everyChangedMethodCarriesParameterTypes() {
        List<DiffFile> files = parser.parse(DiffResources.diff("cross-signature.diff"));

        List<SignatureChange> changes = extractor.extract(files, crossSignatureSources());

        assertThat(changes).isNotEmpty();
        assertThat(changes).allSatisfy(c ->
                assertThat(c.signature()).matches(".+#.+\\(.*\\)"));
    }

    @Test
    void skipsFilesWithoutSource() {
        List<DiffFile> files = parser.parse(DiffResources.diff("cross-signature.diff"));

        // 不给任何源码：无法做 AST 提取，直接跳过（不猜）
        assertThat(extractor.extract(files, Map.of())).isEmpty();
    }

    @Test
    void ignoresNonJavaFiles() {
        String diff = """
                diff --git a/config.xml b/config.xml
                index 1111111..2222222 100644
                --- a/config.xml
                +++ b/config.xml
                @@ -1,2 +1,2 @@
                -<timeout>30</timeout>
                +<timeout>60</timeout>
                 <retries>3</retries>
                """;

        assertThat(extractor.extract(parser.parse(diff), Map.of())).isEmpty();
    }

    @Test
    void normalizesGenericParametersWithoutSplittingInsideBrackets() {
        assertThat(ChangedSignatureExtractor.normalizeParams("Map<String, Long> m, int n"))
                .isEqualTo("(Map<String, Long>, int)");
        assertThat(ChangedSignatureExtractor.normalizeParams("")).isEqualTo("()");
        assertThat(ChangedSignatureExtractor.normalizeParams("final String s")).isEqualTo("(String)");
    }
}
