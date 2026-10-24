package com.codemind.rule.domain.rule;

import java.util.Objects;

/** 打分排序后的规则切块（M11）。 */
public record ScoredRuleChunk(RuleChunk chunk, String documentTitle, double score) {

    public ScoredRuleChunk {
        Objects.requireNonNull(chunk, "chunk");
        Objects.requireNonNull(documentTitle, "documentTitle");
    }
}
