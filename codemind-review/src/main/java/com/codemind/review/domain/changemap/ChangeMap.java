package com.codemind.review.domain.changemap;

import com.codemind.review.domain.chunk.SignatureChange;

import java.util.List;
import java.util.Objects;

/**
 * 全局变更地图（ARCHITECTURE 4 值对象、6.2 第 1 步）：一个轻量 LLM 会话产出的宏观视角，
 * 之后注入每一个子会话（占子会话预算 5%，ARCHITECTURE 6.3）。
 *
 * <p>字段分两类：
 * <ul>
 *   <li><b>LLM 产出</b>：{@code intent} / {@code modules} / {@code highRiskFiles}——宏观判断，
 *       失败时降级为空（{@code degraded=true}），不中断 DAG；</li>
 *   <li><b>确定性产出</b>：{@code files} / {@code signatureChanges}——由 AST + diff 提取
 *       （{@link com.codemind.review.domain.chunk.ChangedSignatureExtractor}），
 *       <b>降级时依然完整</b>。M6 验证点「跨文件签名不一致」靠的是这部分，不靠 LLM 把参数类型写全。</li>
 * </ul>
 *
 * @param intent           PR 意图（一句话）
 * @param modules          涉及的模块划分
 * @param highRiskFiles    高风险文件
 * @param files            变更文件路径列表（确定性）
 * @param signatureChanges 方法签名变更（确定性）
 * @param degraded         LLM 部分是否降级
 * @param degradeReason    降级原因（未降级为 null）
 */
public record ChangeMap(
        String intent,
        List<String> modules,
        List<String> highRiskFiles,
        List<String> files,
        List<SignatureChange> signatureChanges,
        boolean degraded,
        String degradeReason) {

    public ChangeMap {
        intent = intent == null ? "" : intent;
        modules = List.copyOf(Objects.requireNonNull(modules, "modules"));
        highRiskFiles = List.copyOf(Objects.requireNonNull(highRiskFiles, "highRiskFiles"));
        files = List.copyOf(Objects.requireNonNull(files, "files"));
        signatureChanges = List.copyOf(Objects.requireNonNull(signatureChanges, "signatureChanges"));
    }

    public static ChangeMap of(String intent, List<String> modules, List<String> highRiskFiles,
                               List<String> files, List<SignatureChange> signatureChanges) {
        return new ChangeMap(intent, modules, highRiskFiles, files, signatureChanges, false, null);
    }

    /** LLM 部分降级：宏观字段为空，确定性部分保留。 */
    public static ChangeMap degraded(String reason, List<String> files, List<SignatureChange> signatureChanges) {
        return new ChangeMap("", List.of(), List.of(), files, signatureChanges, true, reason);
    }

    /** 只取 {@link SignatureChange.Kind#SIGNATURE_CHANGED} 的那些——跨文件比对最关心的信号。 */
    public List<SignatureChange> signatureChangesOnly() {
        return signatureChanges.stream()
                .filter(c -> c.kind() == SignatureChange.Kind.SIGNATURE_CHANGED)
                .toList();
    }

    /** 渲染为注入子会话 / GlobalReduce 的文本块。 */
    public String renderForPrompt() {
        StringBuilder sb = new StringBuilder();
        sb.append("【全局变更地图】\n");
        sb.append("PR 意图: ").append(intent.isBlank() ? "（未知）" : intent).append('\n');
        sb.append("涉及模块: ").append(modules.isEmpty() ? "（未知）" : String.join(", ", modules)).append('\n');
        sb.append("高风险文件: ").append(highRiskFiles.isEmpty() ? "（未知）" : String.join(", ", highRiskFiles)).append('\n');
        sb.append("变更文件（").append(files.size()).append(" 个）: ").append(String.join(", ", files)).append('\n');
        if (signatureChanges.isEmpty()) {
            sb.append("方法签名变更: （无）\n");
        } else {
            sb.append("方法签名变更（AST 确定性提取，可直接引用）:\n");
            for (SignatureChange change : signatureChanges) {
                sb.append("  - ").append(change.describe()).append('\n');
            }
        }
        return sb.toString();
    }
}
