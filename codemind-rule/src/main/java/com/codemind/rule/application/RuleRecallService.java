package com.codemind.rule.application;

import com.codemind.rule.domain.rule.Embedder;
import com.codemind.rule.domain.rule.RuleChunkRepository;
import com.codemind.rule.domain.rule.RuleRanker;
import com.codemind.rule.domain.rule.ScoredRuleChunk;

import java.util.List;

/** 规则召回用例（M11）：embedding(query) → 两路候选 → 打分排序 → 截断。 */
public class RuleRecallService {

    public static final int MAX_TOP_K = 20;
    /** 与 RuleIngestionService.MAX_PROJECT_LENGTH 一致（同 memory MemoryService 的上限约定）。 */
    public static final int MAX_PROJECT_LENGTH = 255;
    /** 与 memory MemoryService.MAX_QUERY_LENGTH 一致：pg_trgm 的 similarity() 对长文本代价高。 */
    public static final int MAX_QUERY_LENGTH = 8000;
    private static final int DEFAULT_TOP_K = 5;
    /** 向量/三元组两路各自取的候选数，不少于 topK。 */
    private static final int MIN_CANDIDATE_LIMIT = 50;

    private final RuleChunkRepository chunkRepository;
    private final Embedder embedder;
    private final RuleRanker ranker;

    public RuleRecallService(RuleChunkRepository chunkRepository, Embedder embedder, RuleRanker ranker) {
        this.chunkRepository = chunkRepository;
        this.embedder = embedder;
        this.ranker = ranker;
    }

    public List<ScoredRuleChunk> recall(String project, String query, Integer topK) {
        if (project == null || project.isBlank()) {
            throw new IllegalArgumentException("project 不能为空");
        }
        if (project.length() > MAX_PROJECT_LENGTH) {
            throw new IllegalArgumentException("project 不能超过 " + MAX_PROJECT_LENGTH + " 字符");
        }
        if (query == null || query.isBlank()) {
            throw new IllegalArgumentException("query 不能为空");
        }
        if (query.length() > MAX_QUERY_LENGTH) {
            throw new IllegalArgumentException("query 不能超过 " + MAX_QUERY_LENGTH + " 字符");
        }
        int k = topK == null ? DEFAULT_TOP_K : topK;
        if (k < 1 || k > MAX_TOP_K) {
            throw new IllegalArgumentException("topK 必须在 [1, " + MAX_TOP_K + "] 内：" + k);
        }

        String text = query.strip();
        int limit = Math.max(k, MIN_CANDIDATE_LIMIT);
        var candidates = chunkRepository.findCandidates(project, text, embedder.embed(text), limit);
        return ranker.rank(candidates, k);
    }
}
