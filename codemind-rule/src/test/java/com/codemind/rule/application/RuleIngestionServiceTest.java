package com.codemind.rule.application;

import com.codemind.rule.domain.rule.Embedder;
import com.codemind.rule.domain.rule.RuleChunk;
import com.codemind.rule.domain.rule.RuleChunkRepository;
import com.codemind.rule.domain.rule.RuleChunkCandidate;
import com.codemind.rule.domain.rule.RuleDocument;
import com.codemind.rule.domain.rule.RuleDocumentRepository;
import com.codemind.rule.domain.rule.RuleDocumentStatus;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

class RuleIngestionServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-24T00:00:00Z");

    private final InMemoryDocumentRepository documentRepository = new InMemoryDocumentRepository();
    private final InMemoryChunkRepository chunkRepository = new InMemoryChunkRepository();
    private final Embedder embedder = text -> new float[] {1f};
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private final RuleIngestionService service =
            new RuleIngestionService(documentRepository, chunkRepository, embedder, clock);

    @Test
    void firstUploadCreatesActiveDocumentWithVersionOne() {
        RuleDocument doc = service.upload("proj", "接口设计规范", "# 标题\n正文内容。");

        assertThat(doc.status()).isEqualTo(RuleDocumentStatus.ACTIVE);
        assertThat(doc.version()).isEqualTo(1);
        assertThat(chunkRepository.chunksByDocument.get(doc.id())).hasSize(1);
    }

    @Test
    void reuploadingSameProjectAndTitleArchivesOldAndIncrementsVersion() {
        RuleDocument first = service.upload("proj", "接口设计规范", "# 标题\n第一版内容。");
        RuleDocument second = service.upload("proj", "接口设计规范", "# 标题\n第二版内容。");

        assertThat(documentRepository.findStatus(first.id())).isEqualTo(RuleDocumentStatus.ARCHIVED);
        assertThat(second.status()).isEqualTo(RuleDocumentStatus.ACTIVE);
        assertThat(second.version()).isEqualTo(2);
    }

    @Test
    void uploadWithNoHeadingProducesZeroChunksWithoutError() {
        RuleDocument doc = service.upload("proj", "空文档", "没有标题的正文。");

        assertThat(chunkRepository.chunksByDocument.getOrDefault(doc.id(), List.of())).isEmpty();
    }

    /** 最小内存实现：只覆盖本测试用到的行为。 */
    private static final class InMemoryDocumentRepository implements RuleDocumentRepository {
        private final AtomicLong idSeq = new AtomicLong(1);
        private final Map<Long, RuleDocumentStatus> statusById = new HashMap<>();
        private final Map<String, Long> activeByProjectAndTitle = new HashMap<>();

        @Override
        public RuleDocument insert(String project, String title, int version, Instant uploadedAt) {
            long id = idSeq.getAndIncrement();
            statusById.put(id, RuleDocumentStatus.ACTIVE);
            activeByProjectAndTitle.put(key(project, title), id);
            return new RuleDocument(id, project, title, RuleDocumentStatus.ACTIVE, version, uploadedAt);
        }

        @Override
        public Optional<Integer> archiveActiveByProjectAndTitle(String project, String title) {
            Long id = activeByProjectAndTitle.remove(key(project, title));
            if (id == null) {
                return Optional.empty();
            }
            statusById.put(id, RuleDocumentStatus.ARCHIVED);
            return Optional.of(1);
        }

        RuleDocumentStatus findStatus(long id) {
            return statusById.get(id);
        }

        private static String key(String project, String title) {
            return project + "::" + title;
        }
    }

    private static final class InMemoryChunkRepository implements RuleChunkRepository {
        private final Map<Long, List<RuleChunk>> chunksByDocument = new HashMap<>();

        @Override
        public void insertAll(long documentId, String project, List<RuleChunk> chunks, List<float[]> embeddings) {
            chunksByDocument.put(documentId, new ArrayList<>(chunks));
        }

        @Override
        public List<RuleChunkCandidate> findCandidates(String project, String queryText, float[] queryEmbedding,
                                                        int limit) {
            return List.of();
        }
    }
}
