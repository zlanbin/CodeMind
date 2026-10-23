package com.codemind.memory.application;

import com.codemind.memory.domain.memory.Memory;
import com.codemind.memory.domain.memory.MemoryContent;
import com.codemind.memory.domain.memory.MemoryKind;
import com.codemind.memory.domain.memory.MemoryRepository;
import com.codemind.memory.domain.memory.MemoryStatus;
import com.codemind.memory.domain.memory.MemoryCandidate;
import com.codemind.memory.domain.recall.MemoryRanker;
import com.codemind.memory.domain.recall.ScoredMemory;
import com.codemind.memory.domain.recall.ScoringPolicy;
import com.codemind.memory.infrastructure.embedding.HashingEmbedder;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MemoryServiceTest {

    // 带纳秒，验证截到微秒
    private static final Instant NOW = Instant.parse("2026-10-23T00:00:00.123456789Z");
    private static final Instant NOW_MICROS = Instant.parse("2026-10-23T00:00:00.123456Z");

    private final FakeRepository repository = new FakeRepository();
    private final ScoringPolicy policy = new ScoringPolicy(Duration.ofDays(30));
    private final MemoryService service = new MemoryService(repository, new HashingEmbedder(),
            new MemoryRanker(policy), policy, new MemorySettings(0.05, 50, 5), Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void writeNormalizesContentDefaultsImportanceAndTruncatesClock() {
        service.write("p", MemoryKind.CONVENTION, "  允许   返回实体 ", null);

        assertThat(repository.lastContent.text()).isEqualTo("允许 返回实体");
        assertThat(repository.lastImportance).isEqualTo(Memory.DEFAULT_IMPORTANCE);
        assertThat(repository.lastNow).isEqualTo(NOW_MICROS);
        assertThat(repository.lastEmbedding).hasSize(HashingEmbedder.DIMENSIONS);
    }

    @Test
    void writeRejectsInvalidInput() {
        assertThatThrownBy(() -> service.write(" ", MemoryKind.CONVENTION, "x", null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.write("p", MemoryKind.CONVENTION, "x", 0.0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.write("p", MemoryKind.CONVENTION, " ", null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(repository.lastContent).isNull();
    }

    @Test
    void recallRanksRepositoryCandidatesWithTheFormula() {
        repository.candidates = List.of(
                new MemoryCandidate(memory(1, 0.9, NOW_MICROS.minus(Duration.ofDays(60))), 0.9, 0),  // 0.2025
                new MemoryCandidate(memory(2, 0.5, NOW_MICROS), 0.6, 0));                            // 0.3

        List<ScoredMemory> ranked = service.recall("p", " 返回实体 ", MemoryKind.CONVENTION, null, null);

        assertThat(ranked).extracting(s -> s.memory().id()).containsExactly(2L, 1L);
        assertThat(repository.lastQuery).isEqualTo("返回实体");
        assertThat(repository.lastProject).isEqualTo("p");
        assertThat(repository.lastKind).isEqualTo(MemoryKind.CONVENTION);
        assertThat(repository.lastLimit).isEqualTo(50);
    }

    @Test
    void recallValidatesTopKAndQuery() {
        assertThatThrownBy(() -> service.recall("p", "q", null, 0, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.recall("p", "q", null, MemoryService.MAX_TOP_K + 1, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.recall("p", " ", null, 5, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.recall("p", "q", null, 5, 1.5)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.recall("p", "q".repeat(MemoryService.MAX_QUERY_LENGTH + 1), null, 5, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(repository.lastQuery).isNull();
    }

    @Test
    void archivesOnlyMemoriesWhoseRetentionFellBelowThreshold() {
        // retention = importance × 0.5^(天/30)：0.8 × 0.5^5 = 0.025 < 0.05 归档；0.8 × 0.5^4 = 0.05 不归档
        Memory stale = memory(1, 0.8, NOW_MICROS.minus(Duration.ofDays(150)));
        Memory boundary = memory(2, 0.8, NOW_MICROS.minus(Duration.ofDays(120)));
        Memory fresh = memory(3, 0.1, NOW_MICROS);
        repository.active = List.of(stale, boundary, fresh);

        assertThat(service.archiveStale("p")).isEqualTo(1);
        assertThat(repository.archived).containsExactly(1L);
        assertThat(repository.archiveExpected).containsExactly(stale.reinforcedAt());
        assertThat(repository.lastProject).isEqualTo("p");
        assertThat(repository.lastCutoff).isEqualTo(policy.archiveCutoff(0.05, NOW_MICROS).orElseThrow());
    }

    @Test
    void archiveCountsOnlyRowsActuallyArchived() {
        repository.active = List.of(memory(1, 0.1, NOW_MICROS.minus(Duration.ofDays(365))));
        repository.archiveSucceeds = false;  // 读出后被并发强化

        assertThat(service.archiveStale("p")).isZero();
    }

    private static Memory memory(long id, double importance, Instant reinforcedAt) {
        return new Memory(id, "p", MemoryKind.CONVENTION, "m" + id, importance, MemoryStatus.ACTIVE,
                reinforcedAt, reinforcedAt);
    }

    private static final class FakeRepository implements MemoryRepository {
        MemoryContent lastContent;
        double lastImportance;
        Instant lastNow;
        float[] lastEmbedding;
        String lastProject;
        Instant lastCutoff;
        String lastQuery;
        MemoryKind lastKind;
        int lastLimit;
        List<MemoryCandidate> candidates = List.of();
        List<Memory> active = List.of();
        boolean archiveSucceeds = true;
        final List<Long> archived = new ArrayList<>();
        final List<Instant> archiveExpected = new ArrayList<>();

        @Override
        public UpsertResult upsert(String project, MemoryKind kind, MemoryContent content, double importance,
                                   float[] embedding, Instant now) {
            lastContent = content;
            lastImportance = importance;
            lastNow = now;
            lastEmbedding = embedding;
            return new UpsertResult(new Memory(1, project, kind, content.text(), importance, MemoryStatus.ACTIVE,
                    now, now), true);
        }

        @Override
        public List<MemoryCandidate> findCandidates(String project, MemoryKind kind, String queryText,
                                                    float[] queryEmbedding, int limit) {
            lastProject = project;
            lastQuery = queryText;
            lastKind = kind;
            lastLimit = limit;
            return candidates;
        }

        /** 不按 cutoff 过滤：模拟预筛放宽的情形，证明服务仍逐条按 retention 精确判定。 */
        @Override
        public List<Memory> findActiveReinforcedBefore(String project, Instant cutoff) {
            lastProject = project;
            lastCutoff = cutoff;
            return active;
        }

        @Override
        public boolean archiveIfUnchanged(long id, Instant expectedReinforcedAt, Instant now) {
            if (archiveSucceeds) {
                archived.add(id);
                archiveExpected.add(expectedReinforcedAt);
            }
            return archiveSucceeds;
        }

        @Override
        public boolean delete(long id) {
            return false;
        }

        @Override
        public Optional<Memory> findById(long id) {
            return Optional.empty();
        }
    }
}
