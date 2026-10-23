package com.codemind.memory.application;

import com.codemind.memory.domain.memory.Embedder;
import com.codemind.memory.domain.memory.Memory;
import com.codemind.memory.domain.memory.MemoryContent;
import com.codemind.memory.domain.memory.MemoryKind;
import com.codemind.memory.domain.memory.MemoryRepository;
import com.codemind.memory.domain.memory.MemoryRepository.UpsertResult;
import com.codemind.memory.domain.recall.MemoryRanker;
import com.codemind.memory.domain.recall.ScoredMemory;
import com.codemind.memory.domain.recall.ScoringPolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

/**
 * L2 记忆用例：写入（强化）、混合召回、归档、人工删除。
 *
 * <p>时间一律从注入的 {@link Clock} 取并截到微秒：PG timestamptz 只存微秒，
 * 不截的话归档时按 reinforcedAt 做的条件更新会对不上。
 */
@Service
public class MemoryService {

    private static final Logger log = LoggerFactory.getLogger(MemoryService.class);

    /** 单次召回上限，防止调用方把整个项目的记忆拉进 prompt。 */
    public static final int MAX_TOP_K = 50;

    /** 召回查询长度上限：pg_trgm 的 similarity() 对长文本代价高，查询一般是一段 diff 摘要。 */
    public static final int MAX_QUERY_LENGTH = 8000;

    private final MemoryRepository repository;
    private final Embedder embedder;
    private final MemoryRanker ranker;
    private final ScoringPolicy policy;
    private final MemorySettings settings;
    private final Clock clock;

    public MemoryService(MemoryRepository repository, Embedder embedder, MemoryRanker ranker,
                         ScoringPolicy policy, MemorySettings settings, Clock clock) {
        this.repository = repository;
        this.embedder = embedder;
        this.ranker = ranker;
        this.policy = policy;
        this.settings = settings;
        this.clock = clock;
    }

    /** @param importance 为 null 时取 {@link Memory#DEFAULT_IMPORTANCE} */
    public UpsertResult write(String project, MemoryKind kind, String content, Double importance) {
        Memory.requireProject(project);
        if (kind == null) {
            throw new IllegalArgumentException("kind 不能为空");
        }
        double value = Memory.requireImportance(importance == null ? Memory.DEFAULT_IMPORTANCE : importance);
        MemoryContent normalized = MemoryContent.of(content);
        return repository.upsert(project, kind, normalized, value, embedder.embed(normalized.text()), now());
    }

    /**
     * @param kind     为 null 表示不限类型
     * @param topK     为 null 时取配置默认值，上限 {@link #MAX_TOP_K}
     * @param minScore 为 null 时取 0
     */
    public List<ScoredMemory> recall(String project, String query, MemoryKind kind, Integer topK, Double minScore) {
        Memory.requireProject(project);
        if (query == null || query.isBlank()) {
            throw new IllegalArgumentException("query 不能为空");
        }
        if (query.length() > MAX_QUERY_LENGTH) {
            throw new IllegalArgumentException("query 不能超过 " + MAX_QUERY_LENGTH + " 字符");
        }
        int k = topK == null ? settings.defaultTopK() : topK;
        if (k < 1 || k > MAX_TOP_K) {
            throw new IllegalArgumentException("topK 必须在 [1, " + MAX_TOP_K + "] 内：" + k);
        }
        double threshold = minScore == null ? 0 : minScore;
        if (!(threshold >= 0 && threshold <= 1)) {
            throw new IllegalArgumentException("minScore 必须在 [0, 1] 内：" + threshold);
        }
        String text = query.strip();
        int limit = Math.max(k, settings.candidateLimit());
        var candidates = repository.findCandidates(project, kind, text, embedder.embed(text), limit);
        return ranker.rank(candidates, now(), k, threshold);
    }

    /** 把 {@code importance × decay} 低于阈值的 ACTIVE 记忆转 ARCHIVED，返回实际归档条数。 */
    public int archiveStale(String project) {
        Memory.requireProject(project);
        Instant now = now();
        int archived = 0;
        Optional<Instant> cutoff = policy.archiveCutoff(settings.archiveThreshold(), now);
        if (cutoff.isEmpty()) {
            return 0;
        }
        for (Memory m : repository.findActiveReinforcedBefore(project, cutoff.get())) {
            if (policy.retention(m.importance(), m.reinforcedAt(), now) < settings.archiveThreshold()
                    && repository.archiveIfUnchanged(m.id(), m.reinforcedAt(), now)) {
                archived++;
            }
        }
        if (archived > 0) {
            log.info("记忆归档 project={} 条数={}", project, archived);
        }
        return archived;
    }

    public boolean delete(long id) {
        boolean deleted = repository.delete(id);
        if (deleted) {
            log.info("人工删除记忆 id={}", id);
        }
        return deleted;
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }
}
