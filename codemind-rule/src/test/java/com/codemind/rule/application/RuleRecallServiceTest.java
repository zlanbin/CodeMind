package com.codemind.rule.application;

import com.codemind.rule.domain.rule.Embedder;
import com.codemind.rule.domain.rule.RuleChunk;
import com.codemind.rule.domain.rule.RuleChunkCandidate;
import com.codemind.rule.domain.rule.RuleChunkRepository;
import com.codemind.rule.domain.rule.RuleRanker;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RuleRecallServiceTest {

    private final RecordingChunkRepository chunkRepository = new RecordingChunkRepository();
    private final Embedder embedder = text -> new float[] {1f};
    private final RuleRanker ranker = new RuleRanker();
    private final RuleRecallService service = new RuleRecallService(chunkRepository, embedder, ranker);

    @Test
    void rejectsBlankQuery() {
        assertThatThrownBy(() -> service.recall("proj", "", null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.recall("proj", null, null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsTopKAboveLimit() {
        assertThatThrownBy(() -> service.recall("proj", "query", 21)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.recall("proj", "query", 0)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void defaultsTopKToFiveWhenNull() {
        chunkRepository.candidatesToReturn = List.of(candidate(1, "A", 0.9), candidate(2, "B", 0.1));

        List<?> result = service.recall("proj", "query", null);

        assertThat(result).hasSize(2);
        assertThat(chunkRepository.lastLimit.get()).isGreaterThanOrEqualTo(5);
    }

    @Test
    void onlyQueriesActiveChunksThroughRepository() {
        chunkRepository.candidatesToReturn = List.of(candidate(1, "A", 0.5));

        service.recall("proj", "query", 3);

        assertThat(chunkRepository.lastProject.get()).isEqualTo("proj");
    }

    private static RuleChunkCandidate candidate(long id, String section, double similarity) {
        RuleChunk chunk = new RuleChunk(id, 1L, "proj", section, section, "内容" + id);
        return new RuleChunkCandidate(chunk, "文档标题", similarity);
    }

    private static final class RecordingChunkRepository implements RuleChunkRepository {
        List<RuleChunkCandidate> candidatesToReturn = List.of();
        final AtomicInteger lastLimit = new AtomicInteger();
        final java.util.concurrent.atomic.AtomicReference<String> lastProject = new java.util.concurrent.atomic.AtomicReference<>();

        @Override
        public void insertAll(long documentId, String project, List<RuleChunk> chunks, List<float[]> embeddings) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<RuleChunkCandidate> findCandidates(String project, String queryText, float[] queryEmbedding,
                                                        int limit) {
            lastLimit.set(limit);
            lastProject.set(project);
            return candidatesToReturn;
        }
    }
}
