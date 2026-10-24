package com.codemind.review.infrastructure.llm;

import com.codemind.review.domain.agent.LlmClient;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.concurrent.ThreadLocalRandom;

/**
 * {@link LlmClient} 的压测替身（M12 Task 5，{@code loadtest} profile 专用）。
 *
 * <p>不调用任何真实 LLM，用固定样例 JSON + 随机延迟模拟真实调用的耗时分布，隔离网络抖动对
 * 压测数据的干扰（见 spec 3.1）。与 {@link SpringAiLlmClient} 的 {@code @Profile("!loadtest")}
 * 互斥，保证 {@code loadtest} profile 下容器里只有这一个 {@link LlmClient} bean。
 *
 * <p>callSite 分三类返回不同结构的样例 JSON：
 * <ul>
 *   <li>{@code CHANGE_MAP}：{@link com.codemind.review.domain.changemap.ChangeMapJsonParser} 期望的结构；</li>
 *   <li>{@code GLOBAL_REDUCE}：{@link com.codemind.review.domain.agent.GlobalReduceAgent} 复用
 *       {@link com.codemind.review.domain.agent.FindingJsonParser}，结构与评审类相同；</li>
 *   <li>其余（{@code STYLE}/{@code SECURITY}/{@code PERFORMANCE}/{@code TEST}，以及任何未预期的新
 *       callSite，含 {@code FINDING_FILTER}）：落到 default 分支，返回评审类样例。
 *       {@code FINDING_FILTER}（{@link com.codemind.review.domain.finding.FindingFilter}）期望的是
 *       {@code {"filtered": true|false}} 这种极简结构，和这里的评审 JSON 不兼容，会导致该场景下
 *       每次都走 LLM 判定失败 -&gt; 放行的降级路径。这是否要在 stub 里单独支持是一个设计决策，
 *       未在本任务范围内处理，已在任务报告里向用户提出，待确认。</li>
 * </ul>
 */
@Component
@Profile("loadtest")
public class StubLlmClient implements LlmClient {

    // STYLE/SECURITY/PERFORMANCE/TEST 共用的评审样例：findings + summary 均合法非空，
    // 避免压测时触发 ChunkSummaryJsonParser 的降级分支（summary.changedSymbols/intent 不能为空）。
    private static final String SAMPLE_FINDING_JSON = """
            {
              "findings": [
                {
                  "file": "src/main/java/com/example/OrderService.java",
                  "line": 42,
                  "severity": "MAJOR",
                  "category": "STYLE",
                  "message": "方法命名不符合团队规范，建议使用动词开头",
                  "suggestion": "将 orderInfo 重命名为 getOrderInfo",
                  "codeSnippet": "public Order orderInfo(Long id) { ... }"
                }
              ],
              "summary": {
                "changedSymbols": ["OrderService#orderInfo(Long)"],
                "intent": "为订单服务新增按 ID 查询接口",
                "risks": [],
                "crossChunkQuestions": []
              }
            }
            """;

    private static final String SAMPLE_CHANGE_MAP_JSON = """
            {
              "intent": "为订单服务新增按 ID 查询接口，并补充相应的单元测试",
              "modules": ["order-service"],
              "highRiskFiles": []
            }
            """;

    private static final String SAMPLE_GLOBAL_REDUCE_FINDING_JSON = """
            {
              "findings": [
                {
                  "file": "src/main/java/com/example/OrderService.java",
                  "line": 0,
                  "severity": "MINOR",
                  "category": "DESIGN",
                  "message": "跨文件调用未校验返回值是否为空",
                  "suggestion": "在调用方补充空值判断",
                  "codeSnippet": "orderRepository.findById(id)"
                }
              ],
              "summary": {
                "changedSymbols": ["OrderService#orderInfo(Long)"],
                "intent": "全局归约：订单查询链路变更",
                "risks": [],
                "crossChunkQuestions": []
              }
            }
            """;

    @Override
    public String complete(String systemPrompt, String userPrompt, String callSite) {
        sleepRandomly();
        return switch (callSite) {
            case "CHANGE_MAP" -> SAMPLE_CHANGE_MAP_JSON;
            case "GLOBAL_REDUCE" -> SAMPLE_GLOBAL_REDUCE_FINDING_JSON;
            default -> SAMPLE_FINDING_JSON; // STYLE/SECURITY/PERFORMANCE/TEST 共用一份评审样例
        };
    }

    /** 确定性区间的随机延迟 [2000ms, 8000ms]，模拟真实 LLM 调用的耗时分布。 */
    private void sleepRandomly() {
        try {
            Thread.sleep(2000 + ThreadLocalRandom.current().nextLong(6001));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
