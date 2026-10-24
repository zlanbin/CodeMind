package com.codemind.review.domain.dag;

import com.codemind.review.domain.agent.AgentReview;
import com.codemind.review.domain.agent.ChunkSummary;
import com.codemind.review.domain.agent.ReviewAgent;
import com.codemind.review.domain.changemap.ChangeMap;
import com.codemind.review.domain.chunk.ReviewChunk;
import com.codemind.review.domain.finding.Finding;
import com.codemind.review.domain.rule.RecalledRule;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ReviewChunkNodeTest {

    private static ReviewChunk chunk() {
        return new ReviewChunk("chunk-1", List.of("A.java"), List.of("A#foo(int)"), "diff", 10, false);
    }

    @Test
    void passesUpstreamRecallRulesResultToAgent() {
        ReviewAgent agent = mock(ReviewAgent.class);
        AgentReview expected = AgentReview.success(List.<Finding>of(), ChunkSummary.of("chunk-1", List.of("A#foo"),
                "intent", List.of(), List.of()), 1);
        when(agent.review(any(), any(), any())).thenReturn(expected);
        List<RecalledRule> rules = List.of(new RecalledRule("规范", "1.1", "标题", "内容", 0.9));

        var node = new ReviewChunkNode("review-chunk-1-style", agent, chunk(), null, "rules-chunk-1");
        NodeContext context = NodeContext.initial("task-1").withCompletedResult("rules-chunk-1", rules);

        AgentReview result = node.execute(context);

        assertThat(result).isSameAs(expected);
        ArgumentCaptor<List<RecalledRule>> rulesCaptor = ArgumentCaptor.forClass(List.class);
        verify(agent).review(eq(chunk()), eq(null), rulesCaptor.capture());
        assertThat(rulesCaptor.getValue()).isEqualTo(rules);
    }

    @Test
    void passesEmptyRulesWhenRecallRulesNodeIdNotSet() {
        ReviewAgent agent = mock(ReviewAgent.class);
        AgentReview expected = AgentReview.success(List.<Finding>of(), ChunkSummary.of("chunk-1", List.of("A#foo"),
                "intent", List.of(), List.of()), 1);
        when(agent.review(any(), any(), any())).thenReturn(expected);

        // 既有三参构造器（M4~M10 既有行为）：不传 recallRulesNodeId
        var node = new ReviewChunkNode("review-chunk-1-style", agent, chunk());

        AgentReview result = node.execute(NodeContext.initial("task-1"));

        assertThat(result).isSameAs(expected);
        verify(agent).review(chunk(), null, List.of());
    }

    @Test
    void passesEmptyRulesWhenUpstreamResultMissing() {
        ReviewAgent agent = mock(ReviewAgent.class);
        AgentReview expected = AgentReview.success(List.<Finding>of(), ChunkSummary.of("chunk-1", List.of("A#foo"),
                "intent", List.of(), List.of()), 1);
        when(agent.review(any(), any(), any())).thenReturn(expected);

        var node = new ReviewChunkNode("review-chunk-1-style", agent, chunk(), null, "rules-chunk-1");

        AgentReview result = node.execute(NodeContext.initial("task-1"));

        assertThat(result).isSameAs(expected);
        verify(agent).review(chunk(), null, List.of());
    }
}
