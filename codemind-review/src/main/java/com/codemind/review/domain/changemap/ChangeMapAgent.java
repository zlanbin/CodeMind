package com.codemind.review.domain.changemap;

import com.codemind.review.domain.agent.LlmClient;
import com.codemind.review.domain.agent.LlmException;
import com.codemind.review.domain.chunk.SignatureChange;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Objects;

/**
 * 全局变更地图 Agent（ARCHITECTURE 6.2 第 1 步）：一个轻量 LLM 会话，
 * <b>只输入文件列表 + 方法签名变更 + diff 统计，不输入 diff 内容</b>。
 *
 * <p>输入范围按 M6 设计审查「修正假设 A」确定：原假设只给文件列表 + 统计，
 * 但 ARCHITECTURE 6.2 要求 ChangeMap 含「方法签名变更」，而签名无法从文件名推断，
 * 故补入 {@link SignatureChange}（由 AST 确定性提取，不需要把 diff 本体投给模型）。
 *
 * <p>容错与 {@link com.codemind.review.domain.agent.ReviewAgent} 一致：解析/调用失败重试，
 * 耗尽则降级——宏观字段为空，但确定性字段（文件列表、签名变更）保留，GlobalReduce 仍有据可依。
 */
public final class ChangeMapAgent {

    private static final Logger log = LoggerFactory.getLogger(ChangeMapAgent.class);

    private static final String SYSTEM_PROMPT = """
            你是一名资深架构师，正在为一个 Pull Request 建立「全局变更地图」，供后续多个子会话评审时参考。
            你只会看到文件列表、方法签名变更和增删行数统计，看不到 diff 内容——请基于这些宏观信息判断。
            只输出 JSON，不要任何解释性文字、不要 markdown 代码围栏。JSON 结构严格如下：
            {
              "intent": "用一句话概括这个 PR 想做什么",
              "modules": ["按文件路径归纳的模块，如 review/domain/chunk"],
              "highRiskFiles": ["最需要重点评审的文件路径，取 diff 规模最大或签名发生变更的"]
            }
            判断依据：
            - 签名变更（尤其是参数类型变化）的文件风险最高，调用方可能没同步改；
            - 增删行数大的文件次之；
            - modules 按文件路径的包结构归纳，不要凭空臆测调用关系。
            intent 必须非空。highRiskFiles 必须是上面给出的文件路径，不要编造。
            """;

    private final LlmClient llmClient;
    private final ChangeMapJsonParser parser;
    private final int maxRetries;

    public ChangeMapAgent(LlmClient llmClient, ChangeMapJsonParser parser, int maxRetries) {
        this.llmClient = Objects.requireNonNull(llmClient, "llmClient");
        this.parser = Objects.requireNonNull(parser, "parser");
        if (maxRetries < 0) {
            throw new IllegalArgumentException("maxRetries 不能为负");
        }
        this.maxRetries = maxRetries;
    }

    /**
     * 构建 ChangeMap。LLM 失败则降级（确定性字段保留），不抛异常中断 DAG。
     *
     * @param stats            每个文件的 diff 统计
     * @param signatureChanges AST 提取的方法签名变更
     */
    public ChangeMap build(List<FileStat> stats, List<SignatureChange> signatureChanges) {
        Objects.requireNonNull(stats, "stats");
        Objects.requireNonNull(signatureChanges, "signatureChanges");
        List<String> files = stats.stream().map(FileStat::path).toList();

        if (stats.isEmpty()) {
            return ChangeMap.degraded("无变更文件", files, signatureChanges);
        }

        String userPrompt = buildUserPrompt(stats, signatureChanges);
        String lastError = null;
        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            try {
                String raw = llmClient.complete(SYSTEM_PROMPT, userPrompt);
                ChangeMap changeMap = parser.parse(raw, files, signatureChanges);
                log.info("ChangeMap 构建完成：{} 个文件、{} 处签名变更、意图「{}」（尝试 {}/{}）",
                        files.size(), signatureChanges.size(), changeMap.intent(), attempt + 1, maxRetries + 1);
                return changeMap;
            } catch (ChangeMapParseException e) {
                lastError = "输出不合法: " + e.getMessage();
                log.warn("ChangeMap 输出解析失败（尝试 {}/{}）: {}", attempt + 1, maxRetries + 1, e.getMessage());
            } catch (LlmException e) {
                lastError = "LLM 调用失败: " + e.getMessage();
                log.warn("ChangeMap LLM 调用失败（尝试 {}/{}）: {}", attempt + 1, maxRetries + 1, e.getMessage());
            }
        }

        log.error("ChangeMap 重试 {} 次仍失败，降级（保留 {} 处确定性签名变更）: {}",
                maxRetries, signatureChanges.size(), lastError);
        return ChangeMap.degraded(lastError, files, signatureChanges);
    }

    private String buildUserPrompt(List<FileStat> stats, List<SignatureChange> signatureChanges) {
        StringBuilder sb = new StringBuilder();
        sb.append("变更文件（").append(stats.size()).append(" 个）:\n");
        for (FileStat stat : stats) {
            sb.append("  - ").append(stat.describe()).append('\n');
        }
        sb.append("\n方法签名变更（AST 提取，含参数类型）:\n");
        if (signatureChanges.isEmpty()) {
            sb.append("  （无）\n");
        } else {
            for (SignatureChange change : signatureChanges) {
                sb.append("  - ").append(change.describe()).append('\n');
            }
        }
        return sb.toString();
    }
}
