package com.codemind.review.domain.agent;

import com.codemind.review.domain.chunk.ReviewChunk;
import com.codemind.review.domain.finding.Finding;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Objects;

/**
 * 评审 Agent 领域服务（ARCHITECTURE 5 子会话评审）：组 prompt → 调 {@link LlmClient} → 解析结构化输出。
 *
 * <p>M5 多 Agent 并行：每个 Agent 绑定一个 {@link ReviewDimension}，用专属 prompt 评审；
 * 同一 chunk 的多维度结果按 fingerprint 去重后聚合。
 *
 * <p>容错策略（验证点：非法输出触发重试/降级）：
 * <ul>
 *   <li>LLM 输出解析失败（{@link FindingParseException}）→ 重试，总尝试次数 = {@code maxRetries + 1}；</li>
 *   <li>LLM 调用失败（{@link LlmException}）→ 同样重试；</li>
 *   <li>重试耗尽仍失败 → 降级：返回空 Findings + 降级原因，不抛异常中断整个 DAG。</li>
 * </ul>
 *
 * <p>纯 domain，不依赖 Spring；{@link LlmClient} 由 infrastructure 注入。
 */
public final class ReviewAgent {

    private static final Logger log = LoggerFactory.getLogger(ReviewAgent.class);

    private static final String BASE_SYSTEM_PROMPT = """
            你是一名资深代码评审专家。针对给定的 unified diff，找出其中的问题并输出结构化评审意见。
            只输出 JSON，不要任何解释性文字、不要 markdown 代码围栏。JSON 结构严格如下：
            {
              "findings": [
                {
                  "file": "文件路径",
                  "line": 行号（整数，新文件侧；文件级问题填 0），
                  "severity": "BLOCKER|CRITICAL|MAJOR|MINOR|INFO 之一",
                  "category": "STYLE|SECURITY|PERFORMANCE|TEST|CORRECTNESS|DESIGN 之一",
                  "message": "问题描述",
                  "suggestion": "修改建议",
                  "codeSnippet": "问题所在的关键代码片段"
                }
              ]
            }
            若没有发现问题，返回 {"findings": []}。severity 和 category 必须用上面列出的英文枚举值。
            """;

    private final ReviewDimension dimension;
    private final LlmClient llmClient;
    private final FindingJsonParser parser;
    private final int maxRetries;

    public ReviewAgent(ReviewDimension dimension, LlmClient llmClient, FindingJsonParser parser, int maxRetries) {
        this.dimension = Objects.requireNonNull(dimension, "dimension");
        this.llmClient = Objects.requireNonNull(llmClient, "llmClient");
        this.parser = Objects.requireNonNull(parser, "parser");
        if (maxRetries < 0) {
            throw new IllegalArgumentException("maxRetries 不能为负");
        }
        this.maxRetries = maxRetries;
    }

    /**
     * 评审单个 chunk。解析/调用失败按策略重试，耗尽则降级（不抛异常）。
     */
    public AgentReview review(ReviewChunk chunk) {
        Objects.requireNonNull(chunk, "chunk");
        String systemPrompt = buildSystemPrompt();
        String userPrompt = buildUserPrompt(chunk);

        String lastError = null;
        int attempts = 0;
        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            attempts++;
            try {
                String raw = llmClient.complete(systemPrompt, userPrompt);
                List<Finding> findings = parser.parse(raw);
                log.info("[{}] chunk {} 评审完成，{} 条意见（尝试 {}/{}）",
                        dimension, chunk.chunkId(), findings.size(), attempt + 1, maxRetries + 1);
                return AgentReview.success(findings, attempts);
            } catch (FindingParseException e) {
                lastError = "输出不合法: " + e.getMessage();
                log.warn("[{}] chunk {} 输出解析失败（尝试 {}/{}）: {}",
                        dimension, chunk.chunkId(), attempt + 1, maxRetries + 1, e.getMessage());
            } catch (LlmException e) {
                lastError = "LLM 调用失败: " + e.getMessage();
                log.warn("[{}] chunk {} LLM 调用失败（尝试 {}/{}）: {}",
                        dimension, chunk.chunkId(), attempt + 1, maxRetries + 1, e.getMessage());
            }
        }

        log.error("[{}] chunk {} 重试 {} 次仍失败，降级为空结果: {}", dimension, chunk.chunkId(), maxRetries, lastError);
        return AgentReview.degraded(lastError, attempts);
    }

    private String buildSystemPrompt() {
        String dimensionGuide = switch (dimension) {
            case STYLE -> """

                    【评审重点：代码风格】
                    关注命名规范、格式一致性、注释质量、可读性。例如：
                    - 变量/方法命名不符合驼峰或含义不明
                    - 缩进/空行不一致
                    - 缺少必要的类/方法注释
                    - 魔法数字未提取为常量
                    不要报告安全、性能、测试相关问题，只聚焦代码风格。
                    """;
            case SECURITY -> """

                    【评审重点：安全】
                    关注注入漏洞、认证授权缺陷、敏感数据泄露。例如：
                    - SQL 注入、XSS、命令注入
                    - 未验证用户输入
                    - 密码/密钥硬编码
                    - 权限检查缺失
                    不要报告风格、性能、测试相关问题，只聚焦安全。
                    """;
            case PERFORMANCE -> """

                    【评审重点：性能】
                    关注算法复杂度、资源泄漏、不必要的计算。例如：
                    - N+1 查询、循环中的重复查询
                    - 未关闭的流/连接
                    - 不必要的对象创建、深拷贝
                    - 低效的数据结构选择
                    不要报告风格、安全、测试相关问题，只聚焦性能。
                    """;
            case TEST -> """

                    【评审重点：测试】
                    关注测试覆盖、测试质量、边界用例。例如：
                    - 新增公共方法无对应测试
                    - 测试断言不充分
                    - 缺少边界/异常场景测试
                    - 测试用例命名不清晰
                    不要报告风格、安全、性能相关问题，只聚焦测试。
                    """;
        };
        return BASE_SYSTEM_PROMPT + dimensionGuide;
    }

    public ReviewDimension dimension() {
        return dimension;
    }

    private String buildUserPrompt(ReviewChunk chunk) {
        return """
                请评审以下代码变更。
                涉及文件: %s
                变更符号: %s

                unified diff:
                %s
                """.formatted(
                String.join(", ", chunk.files()),
                chunk.changedSymbols().isEmpty() ? "（无）" : String.join(", ", chunk.changedSymbols()),
                chunk.diffContent());
    }
}
