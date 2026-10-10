package com.codemind.review.domain.task;

import com.codemind.review.domain.agent.ChunkSummary;
import com.codemind.review.domain.changemap.ChangeMap;
import com.codemind.review.domain.chunk.ReviewChunk;
import com.codemind.review.domain.finding.Finding;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * 评审任务聚合根。M1 有标识、来源和状态机；M2 挂上语义切片结果 {@link ReviewChunk}；
 * M5 挂上 {@link Finding} 评审结果；M6 挂上 {@link ChangeMap} 与各片 {@link ChunkSummary}。
 *
 * <p>各片摘要按 {@code chunkId} 索引存放（ARCHITECTURE 4 把 ChunkSummary 画在 ReviewChunk 下，
 * 这里用 Map 关联而非塞进 {@link ReviewChunk} 值对象——切片是规划阶段产物、摘要是评审阶段产物，
 * 分开存不必为了挂摘要重建切片，也不用动 {@code review_chunk} 表结构）。
 *
 * <p>纯 Java，不依赖 Spring 和持久化框架，映射在 infrastructure 层。
 */
public class ReviewTask {

    private final String taskId;
    private final String repo;
    private final int prNumber;
    private final String headSha;
    private final String baseSha;
    private final String sourceEventId;
    private final Instant createdAt;
    private final List<ReviewChunk> chunks = new ArrayList<>();
    private final List<Finding> findings = new ArrayList<>();
    private final Map<String, ChunkSummary> chunkSummaries = new LinkedHashMap<>();

    private ReviewTaskStatus status;
    private ChangeMap changeMap;

    private ReviewTask(String taskId, String repo, int prNumber, String headSha, String baseSha,
                       String sourceEventId, ReviewTaskStatus status, Instant createdAt) {
        this.taskId = Objects.requireNonNull(taskId, "taskId");
        this.repo = requireText(repo, "repo");
        this.prNumber = prNumber;
        this.headSha = requireText(headSha, "headSha");
        this.baseSha = requireText(baseSha, "baseSha");
        this.sourceEventId = Objects.requireNonNull(sourceEventId, "sourceEventId");
        this.status = Objects.requireNonNull(status, "status");
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
    }

    /** 新建任务，状态为 CREATED。 */
    public static ReviewTask create(String repo, int prNumber, String headSha, String baseSha,
                                    String sourceEventId) {
        if (prNumber <= 0) {
            throw new IllegalArgumentException("prNumber 必须为正数，实际 " + prNumber);
        }
        return new ReviewTask(UUID.randomUUID().toString(), repo, prNumber, headSha, baseSha,
                sourceEventId, ReviewTaskStatus.CREATED, Instant.now());
    }

    /** 从持久化状态重建聚合，仓储实现用。 */
    public static ReviewTask rehydrate(String taskId, String repo, int prNumber, String headSha,
                                       String baseSha, String sourceEventId,
                                       ReviewTaskStatus status, Instant createdAt) {
        return new ReviewTask(taskId, repo, prNumber, headSha, baseSha, sourceEventId,
                status, createdAt);
    }

    /**
     * 推进状态。
     *
     * @throws IllegalStateException 目标状态不在当前状态的允许集合内
     */
    public void transitionTo(ReviewTaskStatus target) {
        Objects.requireNonNull(target, "target");
        if (!status.canTransitionTo(target)) {
            throw new IllegalStateException(
                    "非法状态流转: %s → %s (taskId=%s)".formatted(status, target, taskId));
        }
        this.status = target;
    }

    /**
     * 挂上 PlanChunks 节点产出的语义切片（M2）。切片是规划阶段的结果，调用方负责在
     * {@code CREATED → PLANNING} 之后设置。重复调用以最新一次为准（续跑重算场景）。
     */
    public void assignChunks(List<ReviewChunk> newChunks) {
        Objects.requireNonNull(newChunks, "chunks");
        this.chunks.clear();
        this.chunks.addAll(newChunks);
    }

    /** 只读视图，外部不能直接改聚合内部集合。 */
    public List<ReviewChunk> chunks() {
        return Collections.unmodifiableList(chunks);
    }

    /**
     * 挂上评审结果（M5）。多个 chunk 归约后的所有 Finding 汇总到任务级别。
     * 重复调用以最新一次为准（续跑场景）。
     */
    public void assignFindings(List<Finding> newFindings) {
        Objects.requireNonNull(newFindings, "findings");
        this.findings.clear();
        this.findings.addAll(newFindings);
    }

    /** 只读视图。 */
    public List<Finding> findings() {
        return Collections.unmodifiableList(findings);
    }

    /** 挂上全局变更地图（M6）。规划阶段产物，重复调用以最新一次为准。 */
    public void assignChangeMap(ChangeMap newChangeMap) {
        this.changeMap = Objects.requireNonNull(newChangeMap, "changeMap");
    }

    /** 全局变更地图；未构建时为 null。 */
    public ChangeMap changeMap() {
        return changeMap;
    }

    /** 挂上某个切片的结构化摘要（M6）。同 chunkId 重复调用以最新一次为准（续跑重评场景）。 */
    public void assignChunkSummary(String chunkId, ChunkSummary summary) {
        Objects.requireNonNull(chunkId, "chunkId");
        this.chunkSummaries.put(chunkId, Objects.requireNonNull(summary, "summary"));
    }

    /** 只读视图：chunkId → 该片摘要。 */
    public Map<String, ChunkSummary> chunkSummaries() {
        return Collections.unmodifiableMap(chunkSummaries);
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " 不能为空");
        }
        return value;
    }

    public String taskId() {
        return taskId;
    }

    public String repo() {
        return repo;
    }

    public int prNumber() {
        return prNumber;
    }

    public String headSha() {
        return headSha;
    }

    public String baseSha() {
        return baseSha;
    }

    public String sourceEventId() {
        return sourceEventId;
    }

    public ReviewTaskStatus status() {
        return status;
    }

    public Instant createdAt() {
        return createdAt;
    }
}
