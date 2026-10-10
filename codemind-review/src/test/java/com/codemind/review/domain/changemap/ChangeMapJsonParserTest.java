package com.codemind.review.domain.changemap;

import com.codemind.review.domain.chunk.SignatureChange;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link ChangeMapJsonParser} 单测（M6 设计审查检查清单：定义 ChangeMap 的 JSON schema 和解析器）。
 */
class ChangeMapJsonParserTest {

    private final ChangeMapJsonParser parser = new ChangeMapJsonParser();

    private static final List<String> FILES = List.of("RefundClient.java", "PaymentGateway.java");
    private static final List<SignatureChange> SIGNATURES = List.of(
            new SignatureChange("RefundClient.java", "RefundClient#submitRefund(long, int)",
                    "RefundClient#submitRefund(String, int)", SignatureChange.Kind.SIGNATURE_CHANGED));

    @Test
    void parsesIntentModulesAndHighRiskFiles() throws Exception {
        String raw = """
                {"intent": "退款票据号类型从 String 改为 long",
                 "modules": ["demo/payment"],
                 "highRiskFiles": ["RefundClient.java"]}
                """;

        ChangeMap map = parser.parse(raw, FILES, SIGNATURES);

        assertThat(map.intent()).isEqualTo("退款票据号类型从 String 改为 long");
        assertThat(map.modules()).containsExactly("demo/payment");
        assertThat(map.highRiskFiles()).containsExactly("RefundClient.java");
        assertThat(map.degraded()).isFalse();
    }

    @Test
    void keepsDeterministicFieldsFromCaller() throws Exception {
        String raw = "{\"intent\": \"改签名\"}";

        ChangeMap map = parser.parse(raw, FILES, SIGNATURES);

        // 文件列表与签名变更不由 LLM 产出，原样保留
        assertThat(map.files()).isEqualTo(FILES);
        assertThat(map.signatureChanges()).isEqualTo(SIGNATURES);
        assertThat(map.signatureChangesOnly()).hasSize(1);
        assertThat(map.modules()).isEmpty();
        assertThat(map.highRiskFiles()).isEmpty();
    }

    @Test
    void rejectsMissingIntent() {
        assertThatThrownBy(() -> parser.parse("{\"modules\": [\"a\"]}", FILES, SIGNATURES))
                .isInstanceOf(ChangeMapParseException.class)
                .hasMessageContaining("intent");
    }

    @Test
    void rejectsNonJson() {
        assertThatThrownBy(() -> parser.parse("我觉得这个 PR 是在改退款", FILES, SIGNATURES))
                .isInstanceOf(ChangeMapParseException.class);
        assertThatThrownBy(() -> parser.parse("", FILES, SIGNATURES))
                .isInstanceOf(ChangeMapParseException.class)
                .hasMessageContaining("空");
    }

    @Test
    void degradedMapStillCarriesSignatureChanges() {
        ChangeMap degraded = ChangeMap.degraded("LLM 超时", FILES, SIGNATURES);

        assertThat(degraded.degraded()).isTrue();
        assertThat(degraded.intent()).isEmpty();
        // 关键：降级后确定性部分仍在，GlobalReduce 依然有据可依
        assertThat(degraded.signatureChangesOnly()).hasSize(1);
        assertThat(degraded.renderForPrompt())
                .contains("RefundClient#submitRefund(String, int)")
                .contains("RefundClient#submitRefund(long, int)");
    }
}
