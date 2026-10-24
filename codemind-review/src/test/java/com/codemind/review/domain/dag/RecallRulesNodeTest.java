package com.codemind.review.domain.dag;

import com.codemind.review.domain.chunk.ReviewChunk;
import com.codemind.review.domain.rule.RecalledRule;
import com.codemind.review.domain.rule.RuleRecallException;
import com.codemind.review.domain.rule.RuleRecallPort;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RecallRulesNodeTest {

    private static ReviewChunk chunk() {
        return new ReviewChunk("chunk-1", List.of("A.java"), List.of("A#foo(int)"), "diff", 10, false);
    }

    @Test
    void returnsRecalledRulesOnSuccess() {
        RuleRecallPort port = (project, query, topK) ->
                List.of(new RecalledRule("规范", "1.1", "标题", "内容", 0.9));
        var node = new RecallRulesNode("rules-chunk-1", port, "org/repo", chunk());

        List<RecalledRule> result = node.execute(NodeContext.initial("task-1"));

        assertThat(result).hasSize(1);
        assertThat(result.get(0).sectionPath()).isEqualTo("1.1");
    }

    @Test
    void returnsEmptyListWhenPortThrowsRuleRecallException() {
        RuleRecallPort failing = (project, query, topK) -> {
            throw new RuleRecallException("召回失败", new RuntimeException("connection refused"));
        };
        var node = new RecallRulesNode("rules-chunk-1", failing, "org/repo", chunk());

        List<RecalledRule> result = node.execute(NodeContext.initial("task-1"));

        assertThat(result).isEmpty();
    }

    @Test
    void queryTextJoinsChangedSymbolsAndFiles() {
        String[] capturedQuery = new String[1];
        RuleRecallPort capturing = (project, query, topK) -> {
            capturedQuery[0] = query;
            return List.of();
        };
        var node = new RecallRulesNode("rules-chunk-1", capturing, "org/repo", chunk());

        node.execute(NodeContext.initial("task-1"));

        assertThat(capturedQuery[0]).contains("A#foo(int)").contains("A.java");
    }
}
