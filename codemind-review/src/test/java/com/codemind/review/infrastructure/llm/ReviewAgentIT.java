package com.codemind.review.infrastructure.llm;

import com.codemind.review.domain.agent.AgentReview;
import com.codemind.review.domain.agent.ReviewAgent;
import com.codemind.review.domain.agent.ReviewDimension;
import com.codemind.review.domain.chunk.ReviewChunk;
import com.codemind.review.domain.finding.Finding;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 真实 LLM 集成测试（ROADMAP M4 验证点：样例 diff 输出合法 Finding JSON）。
 *
 * <p>走真实中转端点（{@code application.yml} 的 {@code spring.ai.openai.base-url}）。
 * 仅当环境变量 {@code CODEMIND_LLM_API_KEY} 存在时执行，否则 {@code assumeTrue} 跳过——
 * 占位 key 无法真正调用。前置：{@code export CODEMIND_LLM_API_KEY=<key>}。
 *
 * <p>非法输出的重试/降级路径见 {@code ReviewAgentTest}（真实模型无法确定性复现畸形输出）。
 */
@SpringBootTest
class ReviewAgentIT {

    @org.springframework.test.context.bean.override.convention.TestBean(name = "rocketMqDomainEventPublisher", methodName = "publicationTestPublisher")
    private com.codemind.review.domain.event.DomainEventPublisher publicationTestPublisher;

    static com.codemind.review.domain.event.DomainEventPublisher publicationTestPublisher() {
        return new com.codemind.review.support.RecordingDomainEventPublisher();
    }

    // 含明显问题（空指针风险 + 异常吞没）的 diff，诱导模型产出 Finding
    private static final String DIFF = """
            diff --git a/PaymentService.java b/PaymentService.java
            index 1111111..2222222 100644
            --- a/PaymentService.java
            +++ b/PaymentService.java
            @@ -10,4 +10,8 @@ public class PaymentService {
                 public void pay(Order order) {
            -        validate(order);
            +        try {
            +            String token = order.getToken();
            +            gateway.charge(token.trim(), order.getAmount());
            +        } catch (Exception e) {
            +        }
                 }
            """;

    /** M5 起按维度装配，注入整个 Map；本用例取 SECURITY 维度作代表验证真实输出可解析。 */
    @Autowired
    private Map<ReviewDimension, ReviewAgent> reviewAgents;

    @Test
    void realLlmProducesLegalFindingJson() {
        assumeTrue(hasRealKey(), "未设置真实 CODEMIND_LLM_API_KEY，跳过真实 LLM 调用");

        ReviewChunk chunk = new ReviewChunk(
                "chunk-pay", List.of("PaymentService.java"), List.of("PaymentService#pay"),
                DIFF, 120, false);

        ReviewAgent reviewAgent = reviewAgents.get(ReviewDimension.SECURITY);
        assertNotNull(reviewAgent, "SECURITY 维度 Agent 应已装配");

        AgentReview review = reviewAgent.review(chunk);

        // 验证点核心：真实模型输出能被解析为合法 Finding JSON（未降级即说明解析成功）
        assertFalse(review.degraded(),
                () -> "真实 LLM 输出未能解析为合法 Finding JSON，降级原因: " + review.degradeReason());

        // 结构完整性：每条 Finding 字段合法（record 构造已校验，这里再核验指纹非空）
        List<Finding> findings = review.findings();
        for (Finding f : findings) {
            assertFalse(f.file().isBlank());
            assertNotNull(f.severity());
            assertNotNull(f.category());
            assertFalse(f.fingerprint().isBlank());
        }
        System.out.println("[" + reviewAgent.dimension() + "] 真实 LLM 返回 " + findings.size()
                + " 条 Finding（attempts=" + review.attempts() + "）");
    }

    private static boolean hasRealKey() {
        String key = System.getenv("CODEMIND_LLM_API_KEY");
        return key != null && !key.isBlank() && !key.equals("placeholder-no-key");
    }
}
