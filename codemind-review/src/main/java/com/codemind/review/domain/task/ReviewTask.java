package com.codemind.review.domain.task;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * 评审任务聚合根。M1 只有标识、来源和状态机；ChangeMap / ReviewChunk / Finding 在 M2、M3、M5 补。
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

    private ReviewTaskStatus status;

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
