package com.codemind.memory.domain.feedback;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 真实 LLM 的抽取质量（M9 T5，宽松断言：措辞不稳定）。无真实 key 时跳过——按验证门，跳过即「未验证」。
 */
@SpringBootTest
class FeedbackExtractorIT {

    @BeforeAll
    static void requireRealKey() {
        String key = System.getenv("CODEMIND_LLM_API_KEY");
        assumeTrue(key != null && !key.isBlank() && !key.startsWith("placeholder"), "需要真实 CODEMIND_LLM_API_KEY");
    }

    @Autowired
    FeedbackExtractor extractor;

    @Test
    void reasonedReplyBecomesAGeneralizedRuleInAllowedCategory() throws Exception {
        Lessons lessons = extractor.extract(new FeedbackInput("o/r", false,
                "不适用，因为本项目的 Controller 约定直接返回 JPA 实体，不用 DTO",
                "**维度：DESIGN · 严重度：MAJOR**\n\n描述：OrderController#get 直接返回了 Order 实体，暴露了持久化模型\n\n"
                        + "建议：引入 OrderDto 并在 Controller 层转换", List.of("DESIGN")));

        System.out.println("抽取结果：" + lessons);
        assertThat(lessons.rejected()).isTrue();
        assertThat(lessons.degraded()).isFalse();
        assertThat(lessons.rules()).isNotEmpty().allSatisfy(r -> {
            assertThat(r.category()).isEqualTo("DESIGN");
            assertThat(r.rule()).doesNotContain("OrderController#get");
        });
    }

    /** T6 实测复现：👎 曾被概括成「本项目要求校验参数」——复述了原意见，方向反了。 */
    @Test
    void thumbsDownRuleIsTheOppositeOfTheOriginalAdvice() throws Exception {
        Lessons lessons = extractor.extract(new FeedbackInput("o/r", true, null,
                "**维度：SECURITY · 严重度：CRITICAL**\n\n描述：未对输入参数 sku 进行校验，可能导致 NPE 或注入攻击\n\n"
                        + "建议：在方法开头添加参数校验：if (sku == null || sku.trim().isEmpty()) return false;",
                List.of("SECURITY")));

        System.out.println("抽取结果：" + lessons);
        assertThat(lessons.degraded()).isFalse();
        assertThat(lessons.rules()).singleElement().satisfies(r -> {
            assertThat(r.rule()).containsAnyOf("允许", "不要求", "不必", "不需要", "无需");
            // 方向反了的样子是「要求 / 必须 / 需要校验」且前面没有否定词（「不要求」是对的）
            assertThat(r.rule()).doesNotContainPattern("(?<![不无])(要求|必须|需要)(对|进行|添加)?.{0,6}校验");
        });
    }

    /** T6 实测场景：合并了 4 维度的评论，回复只否决其中两个。 */
    @Test
    void replyNamingSomeDimensionsOnlyLearnsThose() throws Exception {
        Lessons lessons = extractor.extract(new FeedbackInput("o/r", false,
                "SECURITY 和 PERFORMANCE 不适用，sku 来自内部、stock 单线程访问",
                """
                **维度：CORRECTNESS · 严重度：BLOCKER**
                描述：stock.get(sku) 在 sku 不存在时返回 null，会导致 NPE
                **维度：TEST · 严重度：BLOCKER**
                描述：缺少 SKU 不存在场景的测试
                **维度：SECURITY · 严重度：CRITICAL**
                描述：未对输入参数 sku 进行校验
                **维度：PERFORMANCE · 严重度：MAJOR**
                描述：HashMap 未做同步保护，高并发下有竞态""",
                List.of("CORRECTNESS", "TEST", "SECURITY", "PERFORMANCE")));

        System.out.println("抽取结果：" + lessons);
        assertThat(lessons.rules()).extracting(Lessons.Rule::category)
                .isNotEmpty().allMatch(c -> c.equals("SECURITY") || c.equals("PERFORMANCE"));
    }

    @Test
    void thanksReplyIsNotARejection() throws Exception {
        Lessons lessons = extractor.extract(new FeedbackInput("o/r", false, "好的，谢谢，已经改了",
                "描述：循环条件 i <= size() 越界\n\n建议：改为 i < size()", List.of("CORRECTNESS")));

        System.out.println("抽取结果：" + lessons);
        assertThat(lessons.rejected()).isFalse();
    }
}
