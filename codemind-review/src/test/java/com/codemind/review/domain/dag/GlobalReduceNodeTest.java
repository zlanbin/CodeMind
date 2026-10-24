package com.codemind.review.domain.dag;

import com.codemind.review.domain.agent.*;
import com.codemind.review.domain.changemap.ChangeMap;
import com.codemind.review.domain.chunk.SignatureChange;
import com.codemind.review.domain.finding.Category;
import com.codemind.review.domain.finding.Finding;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link GlobalReduceNode} 单测：节点层面的输入装配、摘要缺失容忍与降级行为。
 *
 * <p>LLM 用 {@link SignatureAwareLlmClient}：它不是返回死字符串的桩，而是<b>按 prompt 内容判断</b>——
 * 只有当 prompt 里同时出现「定义方的新签名」和「调用方引用的旧签名形态」时才报问题，
 * 否则返回空。这样断言证明的是「送进归约会话的信息足够」（设计审查 Q7 的核心疑问），
 * 而不是「桩会不会说话」。
 *
 * <p>端到端的里程碑验证点（真实切片器把调用方与定义方分片）在
 * {@link CrossSessionMapReduceTest#callerAndCalleeLandInDifferentChunksAndGlobalReduceCatchesMismatch()}。
 */
class GlobalReduceNodeTest {

    private static final ChangeMap CHANGE_MAP = ChangeMap.of(
            "退款票据号类型从 String 改为 long",
            List.of("demo/payment"),
            List.of("RefundClient.java"),
            List.of("RefundClient.java", "PaymentGateway.java"),
            List.of(new SignatureChange("RefundClient.java",
                    "RefundClient#submitRefund(long, int)",
                    "RefundClient#submitRefund(String, int)",
                    SignatureChange.Kind.SIGNATURE_CHANGED)));

    /** 定义方那一片的摘要。 */
    private static final ChunkSummary CALLEE_SUMMARY = ChunkSummary.of(
            "chunk-callee",
            List.of("RefundClient#submitRefund(long, int)"),
            "票据号参数类型由 String 改为 long",
            List.of("老调用方可能还在传字符串"),
            List.of("还有哪些文件在调用 submitRefund？"));

    /** 调用方那一片的摘要——它看不到 RefundClient 的定义。 */
    private static final ChunkSummary CALLER_SUMMARY = ChunkSummary.of(
            "chunk-caller",
            List.of("PaymentGateway#refundOrder(long, int)"),
            "退款时补充审计日志",
            List.of("无"),
            List.of("submitRefund(\"TICKET-\" + orderId, amountCents) 的第一个参数类型对不对？"));

    private static GlobalReduceNode nodeWith(LlmClient llm, List<String> upstreamIds) {
        return new GlobalReduceNode("global-reduce",
                new GlobalReduceAgent(llm, new FindingJsonParser(), 0), upstreamIds, CHANGE_MAP);
    }

    private static NodeContext contextWith(ChunkSummary... summaries) {
        NodeContext context = NodeContext.initial("task-gr");
        for (ChunkSummary summary : summaries) {
            context = context.withCompletedResult("reduce-" + summary.chunkId(),
                    new ChunkReduceResult(summary.chunkId(), List.of(), summary, List.of()));
        }
        return context;
    }

    @Test
    void findsCrossFileSignatureMismatchFromSummariesOnly() throws Exception {
        SignatureAwareLlmClient llm = new SignatureAwareLlmClient();
        GlobalReduceNode node = nodeWith(llm, List.of("reduce-chunk-callee", "reduce-chunk-caller"));

        GlobalReduceResult result = node.execute(contextWith(CALLEE_SUMMARY, CALLER_SUMMARY));

        assertThat(result.degraded()).isFalse();
        assertThat(result.findings()).hasSize(1);
        Finding finding = result.findings().get(0);
        assertThat(finding.file()).isEqualTo("RefundClient.java");
        assertThat(finding.category()).isEqualTo(Category.CORRECTNESS);
        assertThat(finding.message()).containsAnyOf("type", "signature", "类型", "签名");

        // GlobalReduce 看到的只有摘要和变更地图，没有任何 diff
        assertThat(llm.lastUserPrompt).doesNotContain("@@").doesNotContain("diff --git");
        assertThat(llm.lastUserPrompt)
                .contains("RefundClient#submitRefund(long, int)")
                .contains("RefundClient#submitRefund(String, int)")
                .contains("PaymentGateway#refundOrder(long, int)");
    }

    @Test
    void upstreamCrossChunkQuestionsReachTheReducer() throws Exception {
        SignatureAwareLlmClient llm = new SignatureAwareLlmClient();

        nodeWith(llm, List.of("reduce-chunk-callee", "reduce-chunk-caller"))
                .execute(contextWith(CALLEE_SUMMARY, CALLER_SUMMARY));

        assertThat(llm.lastUserPrompt)
                .contains("待回答的跨切片问题")
                .contains("还有哪些文件在调用 submitRefund？");
    }

    @Test
    void reportsNothingWhenOnlyOneSideIsVisible() throws Exception {
        // 只有定义方的摘要：没有任何调用方信息，不该凭空报跨文件问题
        GlobalReduceResult result = nodeWith(new SignatureAwareLlmClient(), List.of("reduce-chunk-callee"))
                .execute(contextWith(CALLEE_SUMMARY));

        assertThat(result.degraded()).isFalse();
        assertThat(result.findings()).isEmpty();
    }

    @Test
    void skipsChunksWithoutSummary() throws Exception {
        NodeContext context = contextWith(CALLEE_SUMMARY, CALLER_SUMMARY)
                .withCompletedResult("reduce-chunk-mute",
                        new ChunkReduceResult("chunk-mute", List.of(), List.of("摘要降级")));

        GlobalReduceResult result = nodeWith(new SignatureAwareLlmClient(),
                List.of("reduce-chunk-callee", "reduce-chunk-caller", "reduce-chunk-mute"))
                .execute(context);

        // 一片摘要缺失不该让整体失效，其余片照常比对
        assertThat(result.degraded()).isFalse();
        assertThat(result.findings()).hasSize(1);
    }

    @Test
    void degradesWhenAllSummariesMissing() throws Exception {
        NodeContext context = NodeContext.initial("task-gr")
                .withCompletedResult("reduce-chunk-mute",
                        new ChunkReduceResult("chunk-mute", List.of(), List.of()));

        GlobalReduceResult result =
                nodeWith(new SignatureAwareLlmClient(), List.of("reduce-chunk-mute")).execute(context);

        assertThat(result.degraded()).isTrue();
        assertThat(result.degradeReason()).contains("没有任何切片摘要");
    }

    @Test
    void degradesWhenLlmOutputMalformed() throws Exception {
        LlmClient garbage = (system, user, callSite) -> "我需要看到完整代码才能判断";

        GlobalReduceResult result = nodeWith(garbage, List.of("reduce-chunk-callee", "reduce-chunk-caller"))
                .execute(contextWith(CALLEE_SUMMARY, CALLER_SUMMARY));

        assertThat(result.degraded()).isTrue();
        assertThat(result.findings()).isEmpty();
    }

    @Test
    void rejectsIncompleteUpstream() {
        GlobalReduceNode node = nodeWith(new SignatureAwareLlmClient(), List.of("reduce-missing"));

        assertThatThrownBy(() -> node.execute(NodeContext.initial("task-gr")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("未完成");
    }

    /**
     * 信息敏感的假 LLM：模拟一个真会读 prompt 的归约会话。
     *
     * <p>只有当 prompt 同时给出「{@code submitRefund} 的新签名首参为 long」和
     * 「某片在以字符串形态调用 submitRefund」时，才报 CORRECTNESS 问题；
     * 信息不全则返回空 findings。
     */
    private static final class SignatureAwareLlmClient implements LlmClient {
        private String lastUserPrompt;

        @Override
        public String complete(String systemPrompt, String userPrompt, String callSite) {
            this.lastUserPrompt = userPrompt;
            boolean calleeDeclaresLong = userPrompt.contains("submitRefund(long");
            boolean calleePreviouslyString = userPrompt.contains("submitRefund(String");
            boolean callerPassesString = userPrompt.contains("submitRefund(\"");
            if (calleeDeclaresLong && calleePreviouslyString && callerPassesString) {
                return """
                        {"findings": [
                          {"file": "RefundClient.java", "line": 0, "severity": "BLOCKER",
                           "category": "CORRECTNESS",
                           "message": "signature mismatch: PaymentGateway#refundOrder 仍以 String 传入票据号，\
                           但 RefundClient#submitRefund 的第一个参数类型已改为 long",
                           "suggestion": "把调用方改为传 long，或为 String 重载保留兼容",
                           "codeSnippet": "RefundClient#submitRefund(long, int)"}
                        ]}
                        """;
            }
            return "{\"findings\": []}";
        }
    }
}
