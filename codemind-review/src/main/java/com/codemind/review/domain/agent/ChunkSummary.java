package com.codemind.review.domain.agent;

import java.util.List;
import java.util.Objects;

/**
 * 子会话结构化摘要（ARCHITECTURE 6.4）：子会话除 Findings 外的第二份产出，
 * 是 GlobalReduce 的<b>唯一</b>输入来源之一（另一个是 ChangeMap）——GlobalReduce 不读原始 diff。
 *
 * <p>{@code changedSymbols} 要求带参数类型（如 {@code OrderService#cancel(Long)}），
 * 否则 GlobalReduce 无法判断跨文件签名是否一致（M6 设计审查 Q5）。prompt 里会明确要求，
 * 同时 ChangeMap 里有 AST 确定性提取的签名作为交叉校验。
 *
 * <p>{@code crossChunkQuestions} 按设计审查的约束，只问「调用者在其他 chunk 且接口有变更」的问题，
 * 不做万能兜底（Q4）。
 *
 * @param chunkId             所属切片
 * @param changedSymbols      本片变更的符号（带参数类型）
 * @param intent              本片变更的意图
 * @param risks               本片识别出的风险
 * @param crossChunkQuestions 上交给 GlobalReduce 的跨 chunk 问题
 * @param degraded            是否降级（LLM 未产出可用摘要）
 * @param degradeReason       降级原因（未降级为 null）
 */
public record ChunkSummary(
        String chunkId,
        List<String> changedSymbols,
        String intent,
        List<String> risks,
        List<String> crossChunkQuestions,
        boolean degraded,
        String degradeReason) {

    public ChunkSummary {
        chunkId = Objects.requireNonNull(chunkId, "chunkId");
        changedSymbols = List.copyOf(Objects.requireNonNull(changedSymbols, "changedSymbols"));
        intent = intent == null ? "" : intent;
        risks = List.copyOf(Objects.requireNonNull(risks, "risks"));
        crossChunkQuestions = List.copyOf(Objects.requireNonNull(crossChunkQuestions, "crossChunkQuestions"));
    }

    public static ChunkSummary of(String chunkId, List<String> changedSymbols, String intent,
                                  List<String> risks, List<String> crossChunkQuestions) {
        return new ChunkSummary(chunkId, changedSymbols, intent, risks, crossChunkQuestions, false, null);
    }

    public static ChunkSummary degraded(String chunkId, String reason) {
        return new ChunkSummary(chunkId, List.of(), "", List.of(), List.of(), true, reason);
    }

    /**
     * 必填字段完整性（M6 设计审查：ChunkReduceNode 要验证 summary 完整性，避免 GlobalReduce 静默失效）。
     * {@code changedSymbols} 与 {@code intent} 非空才算可用。
     */
    public boolean isComplete() {
        return !degraded && !changedSymbols.isEmpty() && !intent.isBlank();
    }

    /** 渲染为投喂 GlobalReduce 的文本块。 */
    public String renderForPrompt() {
        StringBuilder sb = new StringBuilder();
        sb.append("切片 ").append(chunkId).append('\n');
        sb.append("  变更符号: ").append(changedSymbols.isEmpty() ? "（无）" : String.join(", ", changedSymbols)).append('\n');
        sb.append("  意图: ").append(intent.isBlank() ? "（无）" : intent).append('\n');
        sb.append("  风险: ").append(risks.isEmpty() ? "（无）" : String.join("; ", risks)).append('\n');
        sb.append("  跨切片问题: ")
                .append(crossChunkQuestions.isEmpty() ? "（无）" : String.join("; ", crossChunkQuestions))
                .append('\n');
        if (degraded) {
            sb.append("  [本片摘要降级: ").append(degradeReason).append("]\n");
        }
        return sb.toString();
    }
}
