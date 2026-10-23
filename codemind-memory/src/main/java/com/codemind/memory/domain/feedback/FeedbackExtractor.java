package com.codemind.memory.domain.feedback;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;

/**
 * 用 LLM 把一条反馈抽成可复用的否决规则（ARCHITECTURE 7.2 第 3 步）。
 *
 * <p>重试 maxRetries 次后：👎 降级为模板规则（否决是确定的，只是概括不了）；回复抛
 * {@link ExtractionUnavailableException} 交给 MQ 重投——回复可能只是「谢谢已改」，猜错就学到错误记忆。
 */
public final class FeedbackExtractor {

    static final int MAX_RULES = 5;
    static final int MAX_RULE_LENGTH = 300;
    private static final int TEMPLATE_EXCERPT = 200;

    /*
     * T6 实测：旧 prompt 只说「概括成可复用的项目规则」，模型对 👎 把原意见原样复述成规则
     * （「本项目要求校验 sku」），与「否决」方向相反。现在要求规则写成「允许 / 不要求」，并给正反例。
     */
    private static final String SYSTEM_PROMPT = """
            你是代码评审系统的记忆模块。开发者对一条自动评审意见给出了反馈。
            判断反馈是否表示「这类意见在本项目不适用 / 不该再提」。若是，写出一条「否决规则」：
            它描述本项目**允许什么、不要求什么**，让评审以后遇到同类代码时**不再提**这类意见。
            否决规则的方向必须与原评审意见相反——绝不能复述原意见的要求或建议。
            规则要脱离具体行号与变量名，以「本项目允许……」「本项目不要求……」「……不必……」开头的一句话。
            例：
              原意见「Controller 不应直接返回实体，建议引入 DTO」+ 开发者「不适用，我们约定直接返回实体」
              正确：「本项目允许 Controller 直接返回实体，不要求引入 DTO」
              错误：「本项目 Controller 应返回 DTO」（复述了原意见）
            开发者只说了原意见里某几条不适用时，只为那几条写规则；没提到的维度不要写。
            只输出 JSON，不要任何解释：
            {"rejected": true|false, "rules": [{"category": "<必须取自给定的维度>", "rule": "<一句话>"}]}
            rejected 为 false 时 rules 为空数组。""";

    private final LlmClient llm;
    private final int maxRetries;

    public FeedbackExtractor(LlmClient llm, int maxRetries) {
        this.llm = Objects.requireNonNull(llm, "llm");
        if (maxRetries < 0) {
            throw new IllegalArgumentException("maxRetries 不能为负");
        }
        this.maxRetries = maxRetries;
    }

    public Lessons extract(FeedbackInput input) throws ExtractionUnavailableException {
        String user = userPrompt(input);
        String lastError = "";
        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            try {
                Lessons lessons = parse(llm.complete(SYSTEM_PROMPT, user), input.categories());
                if (input.thumbsDown() && !lessons.rejected()) {
                    // 👎 本身就是否决；模型说不是否决等于没给出规则，按模板学
                    return template(input);
                }
                return lessons;
            } catch (LlmClient.LlmException | IllegalArgumentException e) {
                lastError = e.getMessage();
            }
        }
        if (input.thumbsDown()) {
            return template(input);
        }
        throw new ExtractionUnavailableException("回复反馈无法判定是否否决：" + lastError);
    }

    /** 每个维度一条：「开发者否决过这类意见：<评论摘录>」。 */
    static Lessons template(FeedbackInput input) {
        String excerpt = input.commentBody().replaceAll("\\s+", " ").strip();
        if (excerpt.length() > TEMPLATE_EXCERPT) {
            excerpt = excerpt.substring(0, TEMPLATE_EXCERPT);
        }
        List<Lessons.Rule> rules = new ArrayList<>();
        for (String category : new LinkedHashSet<>(input.categories())) {
            rules.add(new Lessons.Rule(category, "开发者否决过这类意见：" + excerpt));
        }
        return new Lessons(true, rules, true);
    }

    static Lessons parse(String raw, List<String> allowedCategories) {
        JsonNode root = LlmJson.readObject(raw);
        JsonNode rejected = root.get("rejected");
        JsonNode rules = root.get("rules");
        if (rejected == null || !rejected.isBoolean() || rules == null || !rules.isArray()) {
            throw new IllegalArgumentException("缺少 rejected / rules：" + LlmJson.preview(raw));
        }
        if (!rejected.booleanValue()) {
            return Lessons.notRejected();
        }
        List<Lessons.Rule> parsed = new ArrayList<>();
        for (JsonNode node : rules) {
            JsonNode category = node.get("category");
            JsonNode rule = node.get("rule");
            if (category == null || !category.isTextual() || rule == null || !rule.isTextual()
                    || rule.textValue().isBlank()) {
                throw new IllegalArgumentException("规则缺字段：" + LlmJson.preview(raw));
            }
            if (rule.textValue().length() > MAX_RULE_LENGTH) {
                throw new IllegalArgumentException("规则过长");
            }
            // 维度只能取自评论里实际出现的。编出来的维度不能归到别的维度上（会把无关维度的意见一起压掉），
            // 整条丢弃；全丢光则按非法输出重试
            String c = category.textValue().strip().toUpperCase(java.util.Locale.ROOT);
            if (allowedCategories.contains(c)) {
                parsed.add(new Lessons.Rule(c, rule.textValue().strip()));
            }
        }
        if (parsed.isEmpty() || parsed.size() > MAX_RULES) {
            throw new IllegalArgumentException("否决规则条数须在 1~" + MAX_RULES + " 之间");
        }
        return new Lessons(true, parsed, false);
    }

    private static String userPrompt(FeedbackInput input) {
        // 👎 已经确定是否决，不让模型再判断：没有回复时它倾向于答 rejected=false（真实 LLM IT 实测）
        String judgement = input.thumbsDown()
                ? "开发者点了 👎，这已经确定是否决：rejected 必须为 true，只需写出否决规则。"
                : "根据回复判断是否否决。";
        return """
                %s
                开发者回复：%s
                评论涉及的维度：%s
                被反馈的评审意见：
                %s""".formatted(judgement,
                input.replyText() == null ? "（无）" : input.replyText(),
                String.join(", ", input.categories()), input.commentBody());
    }

    /** LLM 不可用且无法安全降级（仅回复类反馈）。 */
    public static class ExtractionUnavailableException extends Exception {
        public ExtractionUnavailableException(String message) {
            super(message);
        }
    }
}
