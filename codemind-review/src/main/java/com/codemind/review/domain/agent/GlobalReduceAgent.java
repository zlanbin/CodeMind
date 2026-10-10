package com.codemind.review.domain.agent;

import com.codemind.review.domain.changemap.ChangeMap;
import com.codemind.review.domain.finding.Finding;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Objects;

/**
 * 全局归约 Agent（ARCHITECTURE 6.2 第 4 步）：<b>只读 ChangeMap + 所有 {@link ChunkSummary}，
 * 不读原始 diff 或源码</b>，回答跨 chunk 问题、发现跨文件问题。
 *
 * <p>M6 只做一层归约；摘要过多时的树形递归归约留 M7（M6 设计审查检查清单第 10 项）。
 *
 * <p>输出结构与 chunk 级 Finding 相同（设计审查「明确 GlobalReduce 的输出与合并策略」），
 * 由 {@link GlobalReduceNode} 直接追加到任务级 Findings，不再加 MergeFindings 节点。
 *
 * <p>能发现签名不一致靠两份输入叠加：ChangeMap 里有 AST 确定性提取的签名变更
 * （含参数类型），各片摘要里有本片的 {@code changedSymbols} 和 {@code crossChunkQuestions}。
 * 即便某片的 LLM 漏写了参数类型，ChangeMap 那份仍在（设计审查 Q7）。
 */
public final class GlobalReduceAgent {

    private static final Logger log = LoggerFactory.getLogger(GlobalReduceAgent.class);

    private static final String SYSTEM_PROMPT = """
            你是代码评审的全局归约会话。整个 Pull Request 被切成多个切片，分别由不同会话评审完毕。
            你看不到任何 diff 和源码，只能看到全局变更地图和各个切片的结构化摘要。
            你的职责只有一件：找出<b>单个切片内看不到</b>的跨文件问题。重点是：
            1. 调用方与定义方的方法签名不一致（参数类型、参数个数不匹配）——变更地图里的「方法签名变更」
               是 AST 确定性提取的，可信；若某个切片声明调用了一个签名已变更的方法，就要核对是否对得上；
            2. 回答各切片上交的「跨切片问题」；
            3. 跨文件的行为不一致（如一侧提交、另一侧回滚）。

            不要重复各切片已经报告过的片内问题。不要凭空猜测你看不到的实现细节。
            只输出 JSON，不要任何解释性文字、不要 markdown 代码围栏。JSON 结构严格如下：
            {
              "findings": [
                {
                  "file": "问题所在文件路径，必须取自变更地图给出的文件列表",
                  "line": 0,
                  "severity": "BLOCKER|CRITICAL|MAJOR|MINOR|INFO 之一",
                  "category": "CORRECTNESS",
                  "message": "跨文件问题的描述，要写清是哪两个符号对不上",
                  "suggestion": "修改建议",
                  "codeSnippet": "涉及的方法签名"
                }
              ]
            }
            跨文件逻辑问题一律用 category=CORRECTNESS。没有发现跨文件问题时返回 {"findings": []}，
            不要为了凑数编造问题。
            """;

    private final LlmClient llmClient;
    private final FindingJsonParser parser;
    private final int maxRetries;

    public GlobalReduceAgent(LlmClient llmClient, FindingJsonParser parser, int maxRetries) {
        this.llmClient = Objects.requireNonNull(llmClient, "llmClient");
        this.parser = Objects.requireNonNull(parser, "parser");
        if (maxRetries < 0) {
            throw new IllegalArgumentException("maxRetries 不能为负");
        }
        this.maxRetries = maxRetries;
    }

    /**
     * 跨 chunk 归约。LLM 失败则降级为空结果（不抛异常中断 DAG）。
     *
     * @param changeMap 全局变更地图（含 AST 确定性签名变更）
     * @param summaries 所有切片的摘要
     */
    public GlobalReduceResult reduce(ChangeMap changeMap, List<ChunkSummary> summaries) {
        Objects.requireNonNull(summaries, "summaries");
        if (summaries.isEmpty()) {
            return GlobalReduceResult.degraded("没有任何切片摘要可归约", 0);
        }

        String userPrompt = buildUserPrompt(changeMap, summaries);
        String lastError = null;
        int attempts = 0;
        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            attempts++;
            try {
                String raw = llmClient.complete(SYSTEM_PROMPT, userPrompt);
                List<Finding> findings = parser.parse(raw);
                log.info("全局归约完成：读 {} 份摘要 → {} 条跨文件意见（尝试 {}/{}）",
                        summaries.size(), findings.size(), attempt + 1, maxRetries + 1);
                return GlobalReduceResult.success(findings, attempts);
            } catch (FindingParseException e) {
                lastError = "输出不合法: " + e.getMessage();
                log.warn("全局归约输出解析失败（尝试 {}/{}）: {}", attempt + 1, maxRetries + 1, e.getMessage());
            } catch (LlmException e) {
                lastError = "LLM 调用失败: " + e.getMessage();
                log.warn("全局归约 LLM 调用失败（尝试 {}/{}）: {}", attempt + 1, maxRetries + 1, e.getMessage());
            }
        }

        log.error("全局归约重试 {} 次仍失败，降级为空结果: {}", maxRetries, lastError);
        return GlobalReduceResult.degraded(lastError, attempts);
    }

    private String buildUserPrompt(ChangeMap changeMap, List<ChunkSummary> summaries) {
        StringBuilder sb = new StringBuilder();
        if (changeMap != null) {
            sb.append(changeMap.renderForPrompt()).append('\n');
        }
        sb.append("【各切片摘要】共 ").append(summaries.size()).append(" 份\n");
        for (ChunkSummary summary : summaries) {
            sb.append(summary.renderForPrompt());
        }
        List<String> questions = summaries.stream()
                .flatMap(s -> s.crossChunkQuestions().stream())
                .distinct()
                .toList();
        if (!questions.isEmpty()) {
            sb.append("\n【待回答的跨切片问题】\n");
            for (String q : questions) {
                sb.append("  - ").append(q).append('\n');
            }
        }
        return sb.toString();
    }
}
