package com.codemind.review.infrastructure.persistence;

import com.codemind.review.domain.agent.AgentReview;
import com.codemind.review.domain.agent.ChunkSummary;
import com.codemind.review.domain.agent.GlobalReduceResult;
import com.codemind.review.domain.changemap.ChangeMap;
import com.codemind.review.domain.chunk.ReviewChunk;
import com.codemind.review.domain.chunk.SignatureChange;
import com.codemind.review.domain.dag.ChunkReduceResult;
import com.codemind.review.domain.finding.Category;
import com.codemind.review.domain.finding.Finding;
import com.codemind.review.domain.finding.Severity;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * checkpoint mapper 对每种节点结果的往返（纯单测，不需中间件）。
 *
 * <p>M7a 踩过：{@code ChunkSummary.isComplete()} 是派生方法，被 Jackson 当 is-getter
 * 写成 {@code "complete"} 字段，读回时 record 不认，整个评审在 GlobalReduce 前失败。
 * {@code NodeRunRepositoryIT} 的往返用例用的是 3 参构造器（summary 为 null），没覆盖到。
 * 所以这里每种结果都把字段填满。
 */
class CheckpointObjectMapperFactoryTest {

    private final ObjectMapper mapper = CheckpointObjectMapperFactory.create();

    private static final Finding FINDING = Finding.of("A.java", 12, Severity.MAJOR, Category.CORRECTNESS,
            "空指针风险", "加判空", "token.trim()");

    private static final ChunkSummary SUMMARY = ChunkSummary.of("chunk-0",
            List.of("A#go(int)"), "改了 go 的入参", List.of("调用方未同步"), List.of("B 是否传 int？"));

    @Test
    void chunkReduceResultWithCompleteSummaryRoundTrips() throws Exception {
        ChunkReduceResult original = new ChunkReduceResult("chunk-0", List.of(FINDING), SUMMARY, List.of("降级"));

        assertEquals(original, roundTrip(original));
    }

    @Test
    void agentReviewWithSummaryRoundTrips() throws Exception {
        AgentReview original = AgentReview.success(List.of(FINDING), SUMMARY, 1);

        assertEquals(original, roundTrip(original));
    }

    @Test
    void changeMapWithSignatureChangesRoundTrips() throws Exception {
        ChangeMap original = ChangeMap.of("重构支付", List.of("payment"), List.of("A.java"), List.of("A.java"),
                List.of(new SignatureChange("A.java", "A#go(int)", "A#go(String)",
                        SignatureChange.Kind.SIGNATURE_CHANGED)));

        assertEquals(original, roundTrip(original));
    }

    @Test
    void globalReduceResultRoundTrips() throws Exception {
        GlobalReduceResult original = GlobalReduceResult.success(List.of(FINDING), 2);

        assertEquals(original, roundTrip(original));
    }

    @Test
    void reviewChunkListRoundTrips() throws Exception {
        List<ReviewChunk> original = List.of(new ReviewChunk("chunk-0", List.of("A.java"),
                List.of("A#go(int)"), "diff --git a/A.java b/A.java\n", 42, true));

        assertEquals(original, roundTrip(original));
    }

    /** 派生方法（isComplete 等）不进 JSON：checkpoint 只存 record 组件，读回由组件重算。 */
    @Test
    void derivedAccessorsAreNotSerialized() throws Exception {
        String json = mapper.writeValueAsString(SUMMARY);

        assertFalse(json.contains("\"complete\""), json);
    }

    private Object roundTrip(Object value) throws Exception {
        return mapper.readValue(mapper.writeValueAsString(value), Object.class);
    }
}
