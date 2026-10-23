package com.codemind.memory.domain.feedback;

import java.util.List;
import java.util.Objects;

/**
 * 一次反馈抽取出的否决规则。
 *
 * @param rejected 是否构成否决；false 时 rules 为空，不学习（如「谢谢，已改」）
 * @param rules    每条是「category + 一句可复用的项目规则」
 * @param degraded LLM 不可用时走了模板
 */
public record Lessons(boolean rejected, List<Rule> rules, boolean degraded) {

    public Lessons {
        rules = List.copyOf(rules);
        if (rejected && rules.isEmpty()) {
            throw new IllegalArgumentException("否决至少要有一条规则");
        }
        if (!rejected && !rules.isEmpty()) {
            throw new IllegalArgumentException("未否决不应有规则");
        }
    }

    public static Lessons notRejected() {
        return new Lessons(false, List.of(), false);
    }

    public record Rule(String category, String rule) {
        public Rule {
            Objects.requireNonNull(category, "category");
            if (rule == null || rule.isBlank()) {
                throw new IllegalArgumentException("rule 不能为空");
            }
        }

        /**
         * 写进记忆的正文：带维度前缀，召回时 M9b 的同类判定能看到维度。
         * 形如 {@code [CORRECTNESS] 本项目允许 Controller 直接返回实体}。
         */
        public String memoryContent() {
            return "[" + category + "] " + rule.strip();
        }
    }
}
