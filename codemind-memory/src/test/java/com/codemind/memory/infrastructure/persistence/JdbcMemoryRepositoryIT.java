package com.codemind.memory.infrastructure.persistence;

import com.codemind.memory.domain.memory.Embedder;
import com.codemind.memory.domain.memory.MemoryContent;
import com.codemind.memory.domain.memory.MemoryKind;
import com.codemind.memory.domain.memory.MemoryRepository;
import com.codemind.memory.domain.memory.MemoryRepository.UpsertResult;
import com.codemind.memory.domain.memory.MemoryStatus;
import com.codemind.memory.domain.memory.MemoryCandidate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

@SpringBootTest
class JdbcMemoryRepositoryIT {

    private static final Instant T0 = Instant.parse("2026-10-01T00:00:00Z");

    @Autowired
    MemoryRepository repository;
    @Autowired
    Embedder embedder;
    @Autowired
    JdbcClient jdbc;

    private final String project = "codemind/memory-it-" + UUID.randomUUID();
    private final String otherProject = project + "-other";

    @AfterEach
    void cleanUp() {
        jdbc.sql("delete from memory.memory where project in (:p, :o)")
                .param("p", project).param("o", otherProject).update();
    }

    @Test
    void upsertCreatesThenReinforcesWithoutLoweringImportance() {
        UpsertResult first = write(project, MemoryKind.CONVENTION, "本项目允许 Controller 直接返回实体", 0.4, T0);
        UpsertResult lower = write(project, MemoryKind.CONVENTION, " 本项目允许  Controller 直接返回实体", 0.3,
                T0.plus(Duration.ofDays(1)));
        UpsertResult higher = write(project, MemoryKind.CONVENTION, "本项目允许 Controller 直接返回实体", 0.9,
                T0.plus(Duration.ofDays(2)));

        assertThat(first.created()).isTrue();
        assertThat(lower.created()).isFalse();
        assertThat(lower.memory().id()).isEqualTo(first.memory().id());
        assertThat(lower.memory().importance()).isEqualTo(0.4);
        assertThat(lower.memory().reinforcedAt()).isEqualTo(T0.plus(Duration.ofDays(1)));
        assertThat(higher.memory().importance()).isEqualTo(0.9);
        assertThat(higher.memory().createdAt()).isEqualTo(T0);
    }

    @Test
    void reinforcementNeverMovesReinforcedAtBackwards() {
        write(project, MemoryKind.CONVENTION, "x", 0.5, T0.plus(Duration.ofDays(3)));
        UpsertResult late = write(project, MemoryKind.CONVENTION, "x", 0.5, T0);

        assertThat(late.memory().reinforcedAt()).isEqualTo(T0.plus(Duration.ofDays(3)));
    }

    @Test
    void sameContentUnderDifferentKindIsSeparateMemory() {
        long a = write(project, MemoryKind.CONVENTION, "x", 0.5, T0).memory().id();
        long b = write(project, MemoryKind.COMMON_DEFECT, "x", 0.5, T0).memory().id();

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void candidatesCarryBothChannelSimilaritiesAndRespectFilters() {
        long target = write(project, MemoryKind.REJECTED_PATTERN, "本项目允许 Controller 直接返回实体", 0.5, T0)
                .memory().id();
        long otherKind = write(project, MemoryKind.CONVENTION, "Controller 直接返回实体前要转 DTO", 0.5, T0)
                .memory().id();
        long archived = write(project, MemoryKind.REJECTED_PATTERN, "Controller 返回实体需要审批", 0.5, T0)
                .memory().id();
        repository.archiveIfUnchanged(archived, T0, T0);
        write(otherProject, MemoryKind.REJECTED_PATTERN, "本项目允许 Controller 直接返回实体", 0.5, T0);

        String query = "本项目允许 Controller 直接返回实体";
        List<MemoryCandidate> all = repository.findCandidates(project, null, query, embedder.embed(query), 50);
        List<MemoryCandidate> rejectedOnly = repository.findCandidates(project, MemoryKind.REJECTED_PATTERN,
                query, embedder.embed(query), 50);

        assertThat(all).extracting(c -> c.memory().id()).containsExactlyInAnyOrder(target, otherKind);
        assertThat(rejectedOnly).extracting(c -> c.memory().id()).containsExactly(target);
        MemoryCandidate exact = rejectedOnly.get(0);
        assertThat(exact.vectorSimilarity()).isCloseTo(1.0, within(1e-5));
        assertThat(exact.textSimilarity()).isCloseTo(1.0, within(1e-6));
        assertThat(exact.memory().status()).isEqualTo(MemoryStatus.ACTIVE);
    }

    @Test
    void textChannelReturnsRowsTheVectorLimitWouldCut() {
        // 两条干扰项含查询的全部 bigram（向量更近），但不含任何连续三字（三元组为 0）；
        // 目标向量更远、三元组 > 0。limit=1 时向量一路只会取干扰项，目标只能靠三元组一路进来
        write(project, MemoryKind.CONVENTION, "库存校验验库", 0.5, T0);
        write(project, MemoryKind.CONVENTION, "验库库存校验", 0.5, T0);
        long textHit = write(project, MemoryKind.CONVENTION, "必须校验库存数量", 0.5, T0).memory().id();

        String query = "校验库存";
        List<MemoryCandidate> candidates = repository.findCandidates(project, null, query, embedder.embed(query), 1);

        assertThat(candidates).hasSize(2);
        MemoryCandidate vectorHit = candidates.stream().filter(c -> c.memory().id() != textHit).findFirst().orElseThrow();
        MemoryCandidate target = candidates.stream().filter(c -> c.memory().id() == textHit).findFirst().orElseThrow();
        assertThat(vectorHit.vectorSimilarity()).isGreaterThan(target.vectorSimilarity());
        assertThat(vectorHit.textSimilarity()).isZero();
        assertThat(target.textSimilarity()).isPositive();
    }

    @Test
    void archiveSkipsMemoryReinforcedSinceLoaded() {
        long id = write(project, MemoryKind.CONVENTION, "x", 0.5, T0).memory().id();
        write(project, MemoryKind.CONVENTION, "x", 0.5, T0.plus(Duration.ofDays(1)));

        assertThat(repository.archiveIfUnchanged(id, T0, T0.plus(Duration.ofDays(2)))).isFalse();
        assertThat(repository.archiveIfUnchanged(id, T0.plus(Duration.ofDays(1)), T0.plus(Duration.ofDays(2))))
                .isTrue();
        assertThat(repository.findById(id).orElseThrow().status()).isEqualTo(MemoryStatus.ARCHIVED);
        assertThat(repository.findActiveReinforcedBefore(project, T0.plus(Duration.ofDays(10)))).isEmpty();
    }

    @Test
    void archivePrefilterUsesStrictCutoffAndProjectScope() {
        long old = write(project, MemoryKind.CONVENTION, "old", 0.5, T0).memory().id();
        write(project, MemoryKind.CONVENTION, "at-cutoff", 0.5, T0.plus(Duration.ofDays(1)));
        write(otherProject, MemoryKind.CONVENTION, "old", 0.5, T0);

        assertThat(repository.findActiveReinforcedBefore(project, T0.plus(Duration.ofDays(1))))
                .extracting(m -> m.id()).containsExactly(old);
    }

    @Test
    void rewritingArchivedMemoryRevivesIt() {
        long id = write(project, MemoryKind.CONVENTION, "x", 0.5, T0).memory().id();
        repository.archiveIfUnchanged(id, T0, T0);

        UpsertResult revived = write(project, MemoryKind.CONVENTION, "x", 0.5, T0.plus(Duration.ofDays(1)));

        assertThat(revived.created()).isFalse();
        assertThat(revived.memory().status()).isEqualTo(MemoryStatus.ACTIVE);
    }

    @Test
    void deleteIsPhysical() {
        long id = write(project, MemoryKind.CONVENTION, "x", 0.5, T0).memory().id();

        assertThat(repository.delete(id)).isTrue();
        assertThat(repository.delete(id)).isFalse();
        assertThat(repository.findById(id)).isEmpty();
    }

    private UpsertResult write(String p, MemoryKind kind, String text, double importance, Instant now) {
        MemoryContent content = MemoryContent.of(text);
        return repository.upsert(p, kind, content, importance, embedder.embed(content.text()), now);
    }
}
