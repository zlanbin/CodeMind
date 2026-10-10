package com.codemind.review.domain.dag;

import com.codemind.review.domain.agent.LlmClient;
import com.codemind.review.domain.agent.LlmException;
import com.codemind.review.domain.changemap.ChangeMap;
import com.codemind.review.domain.changemap.ChangeMapAgent;
import com.codemind.review.domain.changemap.ChangeMapJsonParser;
import com.codemind.review.domain.chunk.SignatureChange;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link BuildChangeMapNode} 单测（M6 设计审查检查清单：单测覆盖 BuildChangeMapNode）。
 *
 * <p>重点：
 * <ul>
 *   <li>节点能从 diff + 源码提取带参数类型的方法签名变更；</li>
 *   <li>投给 LLM 的 prompt <b>不含 diff 内容</b>（修正后的假设 A）；</li>
 *   <li>LLM 失败时降级，但确定性的签名变更仍保留。</li>
 * </ul>
 */
class BuildChangeMapNodeTest {

    private static final String DIFF = """
            diff --git a/RefundClient.java b/RefundClient.java
            index 3333333..4444444 100644
            --- a/RefundClient.java
            +++ b/RefundClient.java
            @@ -3,6 +3,6 @@ public class RefundClient {
            -    public void submitRefund(String ticketId, int amountCents) {
            +    public void submitRefund(long ticketId, int amountCents) {
                     send(ticketId, amountCents);
                 }
            """;

    private static final String SOURCE = """
            public class RefundClient {

                public void submitRefund(long ticketId, int amountCents) {
                    send(ticketId, amountCents);
                }

                private void send(long ticketId, int amountCents) {
                }
            }
            """;

    private static NodeContext contextWithDiff() {
        return NodeContext.initial("task-cm")
                .withSharedState("diffText", DIFF)
                .withSharedState("newSources", java.util.Map.of("RefundClient.java", SOURCE));
    }

    private static BuildChangeMapNode nodeWith(LlmClient llm) {
        return new BuildChangeMapNode("build-change-map",
                new ChangeMapAgent(llm, new ChangeMapJsonParser(), 0));
    }

    @Test
    void extractsSignatureChangeWithParameterTypes() throws Exception {
        RecordingLlmClient llm = new RecordingLlmClient("""
                {"intent": "票据号类型改为 long",
                 "modules": ["demo"],
                 "highRiskFiles": ["RefundClient.java"]}
                """);

        ChangeMap map = nodeWith(llm).execute(contextWithDiff());

        assertThat(map.degraded()).isFalse();
        assertThat(map.intent()).isEqualTo("票据号类型改为 long");
        assertThat(map.files()).containsExactly("RefundClient.java");
        assertThat(map.signatureChangesOnly()).hasSize(1);
        SignatureChange change = map.signatureChangesOnly().get(0);
        assertThat(change.signature()).isEqualTo("RefundClient#submitRefund(long, int)");
        assertThat(change.previous()).isEqualTo("RefundClient#submitRefund(String, int)");
    }

    @Test
    void promptCarriesSignaturesAndStatsButNotDiffContent() throws Exception {
        RecordingLlmClient llm = new RecordingLlmClient("{\"intent\": \"改签名\"}");

        nodeWith(llm).execute(contextWithDiff());

        // 修正后的假设 A：给文件列表 + 签名变更 + 增删行数，不给 diff 本体
        assertThat(llm.lastUserPrompt)
                .contains("RefundClient.java")
                .contains("RefundClient#submitRefund(String, int)")
                .contains("RefundClient#submitRefund(long, int)")
                .contains("+1/-1");
        assertThat(llm.lastUserPrompt)
                .doesNotContain("@@")
                .doesNotContain("send(ticketId, amountCents)");
    }

    @Test
    void degradesWhenLlmFailsButKeepsDeterministicSignatures() throws Exception {
        LlmClient failing = (system, user) -> {
            throw new LlmException("端点 503");
        };

        ChangeMap map = nodeWith(failing).execute(contextWithDiff());

        assertThat(map.degraded()).isTrue();
        assertThat(map.degradeReason()).contains("503");
        assertThat(map.intent()).isEmpty();
        // 关键：LLM 挂了，签名变更照样在
        assertThat(map.signatureChangesOnly()).hasSize(1);
    }

    @Test
    void degradesWhenLlmOutputIsMalformed() throws Exception {
        ChangeMap map = nodeWith(new RecordingLlmClient("这个 PR 看起来在改退款逻辑")).execute(contextWithDiff());

        assertThat(map.degraded()).isTrue();
        assertThat(map.degradeReason()).contains("不合法");
        assertThat(map.signatureChangesOnly()).hasSize(1);
    }

    @Test
    void rejectsMissingDiffText() {
        BuildChangeMapNode node = nodeWith(new RecordingLlmClient("{\"intent\": \"x\"}"));

        assertThatThrownBy(() -> node.execute(NodeContext.initial("task-cm")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("diffText");
    }

    /** 记录收到的 prompt，便于断言「投了什么给模型」。 */
    private static final class RecordingLlmClient implements LlmClient {
        private final String response;
        private String lastUserPrompt;

        RecordingLlmClient(String response) {
            this.response = response;
        }

        @Override
        public String complete(String systemPrompt, String userPrompt) {
            this.lastUserPrompt = userPrompt;
            return response;
        }
    }
}
