package com.codemind.review.domain.chunk;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SemanticChunker 单测，覆盖 M2 验证点：
 * 切片符合预期、无方法被截断、每片不超预算（oversized 退化场景除外）。
 *
 * <p>多数用例用 {@link LengthTokenEstimator}（token=字符数）精确控制预算边界；
 * 预算不变量用例用真实 jtokkit 估算。
 */
class SemanticChunkerTest {

    private final DiffParser parser = new DiffParser();

    /** token=字符数的确定性桩，便于精确构造预算边界。 */
    private static final class LengthTokenEstimator implements TokenEstimator {
        @Override
        public int estimate(String text) {
            return text == null ? 0 : text.length();
        }
    }

    @Test
    void singleMethodYieldsOneChunkWithoutTruncation() {
        SemanticChunker chunker = new SemanticChunker(new LengthTokenEstimator());
        List<DiffFile> files = parser.parse(DiffResources.diff("single-method.diff"));
        Map<String, String> sources = Map.of("OrderService.java", DiffResources.source("OrderService.java"));

        List<ReviewChunk> chunks = chunker.chunk(files, sources, ChunkBudget.ofDefault());

        assertThat(chunks).hasSize(1);
        ReviewChunk c = chunks.get(0);
        assertThat(c.changedSymbols()).containsExactly("OrderService#cancel");
        assertThat(c.files()).containsExactly("OrderService.java");
        assertThat(c.oversized()).isFalse();
        // 方法未被截断：hunk 的全部行都在片内
        assertThat(c.diffContent())
                .contains("public void cancel(int id) {")
                .contains("stock = stock + 1;")
                .contains("log(id);")
                .contains("     }");
    }

    @Test
    void differentMethodsBecomeSeparateUnits() {
        SemanticChunker chunker = new SemanticChunker(new LengthTokenEstimator());
        List<DiffFile> files = parser.parse(DiffResources.diff("multi-method.diff"));
        Map<String, String> sources = Map.of("Calculator.java", DiffResources.source("Calculator.java"));

        // 大预算：两个方法装进一片，各自完整
        List<ReviewChunk> big = chunker.chunk(files, sources, ChunkBudget.ofDefault());
        assertThat(big).hasSize(1);
        assertThat(big.get(0).changedSymbols())
                .containsExactlyInAnyOrder("Calculator#add", "Calculator#mul");

        // 预算略小于两方法之和：切成两片，每片一个方法，均不超预算、不截断
        int combined = big.get(0).tokenEstimate();
        List<ReviewChunk> split = chunker.chunk(files, sources, ChunkBudget.ofDiffBudget(combined - 10));
        assertThat(split).hasSize(2);
        assertThat(split).allSatisfy(c -> assertThat(c.oversized()).isFalse());
        assertThat(split).allSatisfy(c -> assertThat(c.changedSymbols()).hasSize(1));
        assertThat(split.get(0).diffContent()).contains("public int add");
        assertThat(split.get(1).diffContent()).contains("public int mul");
    }

    @Test
    void crossFileCallRelationshipMergesIntoOneChunk() {
        SemanticChunker chunker = new SemanticChunker(new LengthTokenEstimator());
        List<DiffFile> files = parser.parse(DiffResources.diff("cross-file.diff"));
        Map<String, String> sources = Map.of(
                "OrderService.java", DiffResources.source("OrderServiceCross.java"),
                "InventoryClient.java", DiffResources.source("InventoryClient.java"));

        List<ReviewChunk> chunks = chunker.chunk(files, sources, ChunkBudget.ofDefault());

        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0).files())
                .containsExactlyInAnyOrder("OrderService.java", "InventoryClient.java");
        assertThat(chunks.get(0).changedSymbols())
                .contains("OrderService#cancel", "InventoryClient#rollback");
    }

    @Test
    void oversizedMethodBecomesOneChunkFlaggedNotTruncated() {
        SemanticChunker chunker = new SemanticChunker(new LengthTokenEstimator());
        List<DiffFile> files = parser.parse(DiffResources.diff("oversized-method.diff"));
        Map<String, String> sources = Map.of("BigService.java", DiffResources.source("BigService.java"));

        // 极小 diff 预算：单个方法就超预算
        List<ReviewChunk> chunks = chunker.chunk(files, sources, ChunkBudget.ofDiffBudget(20));

        assertThat(chunks).hasSize(1);
        ReviewChunk c = chunks.get(0);
        assertThat(c.oversized()).isTrue();
        assertThat(c.changedSymbols()).containsExactly("BigService#compute");
        // 退化但不截断：方法体每一行仍在片内
        assertThat(c.diffContent())
                .contains("public int compute(int a, int b, int c) {")
                .contains("r = r % 1000003;")
                .contains("for (int i = 0; i < 4; i++) r += i * a;")
                .contains("return r;");
    }

    @Test
    void nonJavaFileFallsBackToHunkChunking() {
        SemanticChunker chunker = new SemanticChunker(new LengthTokenEstimator());
        String diff = """
                diff --git a/config.xml b/config.xml
                index 1111111..2222222 100644
                --- a/config.xml
                +++ b/config.xml
                @@ -1,2 +1,2 @@
                -<timeout>30</timeout>
                +<timeout>60</timeout>
                 <retries>3</retries>
                """;
        List<DiffFile> files = parser.parse(diff);

        // 无源码、非 Java：降级为 hunk 切片，不崩
        List<ReviewChunk> chunks = chunker.chunk(files, Map.of(), ChunkBudget.ofDefault());

        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0).files()).containsExactly("config.xml");
        assertThat(chunks.get(0).changedSymbols()).isEmpty();
        assertThat(chunks.get(0).diffContent()).contains("<timeout>60</timeout>");
    }

    @Test
    void everyNonOversizedChunkStaysWithinBudgetWithRealTokenizer() {
        SemanticChunker chunker = new SemanticChunker(new JtokkitTokenEstimator());
        ChunkBudget budget = ChunkBudget.ofDefault();

        List<DiffFile> files = parser.parse(DiffResources.diff("multi-method.diff"));
        Map<String, String> sources = Map.of("Calculator.java", DiffResources.source("Calculator.java"));
        List<ReviewChunk> chunks = chunker.chunk(files, sources, budget);

        assertThat(chunks).isNotEmpty();
        assertThat(chunks).allSatisfy(c -> {
            if (!c.oversized()) {
                assertThat(c.tokenEstimate()).isLessThanOrEqualTo(budget.diffBudget());
            }
        });
    }

    @Test
    void emptyInputYieldsNoChunks() {
        SemanticChunker chunker = new SemanticChunker(new LengthTokenEstimator());
        assertThat(chunker.chunk(List.of(), Map.of(), ChunkBudget.ofDefault())).isEmpty();
    }
}
