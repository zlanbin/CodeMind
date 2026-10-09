package com.codemind.review.domain.dag;

import java.time.Instant;
import java.util.Objects;

/**
 * 节点运行记录（checkpoint），用于失败续跑。
 *
 * <p>状态：{@code RUNNING → COMPLETED / FAILED}。
 */
public final class NodeRun {

    private final String runId;
    private final String taskId;
    private final String nodeId;
    private final NodeRunStatus status;
    private final Object result; // COMPLETED 时非 null
    private final String errorMessage; // FAILED 时非 null
    private final Instant startedAt;
    private final Instant completedAt; // 终态时非 null

    private NodeRun(String runId, String taskId, String nodeId, NodeRunStatus status,
                    Object result, String errorMessage, Instant startedAt, Instant completedAt) {
        this.runId = Objects.requireNonNull(runId, "runId");
        this.taskId = Objects.requireNonNull(taskId, "taskId");
        this.nodeId = Objects.requireNonNull(nodeId, "nodeId");
        this.status = Objects.requireNonNull(status, "status");
        this.result = result;
        this.errorMessage = errorMessage;
        this.startedAt = Objects.requireNonNull(startedAt, "startedAt");
        this.completedAt = completedAt;
    }

    public static NodeRun started(String taskId, String nodeId) {
        return new NodeRun(
                java.util.UUID.randomUUID().toString(),
                taskId, nodeId, NodeRunStatus.RUNNING,
                null, null, Instant.now(), null);
    }

    public NodeRun complete(Object result) {
        if (status != NodeRunStatus.RUNNING) {
            throw new IllegalStateException("只有 RUNNING 状态可以完成");
        }
        return new NodeRun(runId, taskId, nodeId, NodeRunStatus.COMPLETED,
                result, null, startedAt, Instant.now());
    }

    public NodeRun fail(String errorMessage) {
        if (status != NodeRunStatus.RUNNING) {
            throw new IllegalStateException("只有 RUNNING 状态可以失败");
        }
        return new NodeRun(runId, taskId, nodeId, NodeRunStatus.FAILED,
                null, errorMessage, startedAt, Instant.now());
    }

    public static NodeRun rehydrate(String runId, String taskId, String nodeId, NodeRunStatus status,
                                    Object result, String errorMessage, Instant startedAt, Instant completedAt) {
        return new NodeRun(runId, taskId, nodeId, status, result, errorMessage, startedAt, completedAt);
    }

    public String runId() {
        return runId;
    }

    public String taskId() {
        return taskId;
    }

    public String nodeId() {
        return nodeId;
    }

    public NodeRunStatus status() {
        return status;
    }

    public Object result() {
        return result;
    }

    public String errorMessage() {
        return errorMessage;
    }

    public Instant startedAt() {
        return startedAt;
    }

    public Instant completedAt() {
        return completedAt;
    }

    public boolean isCompleted() {
        return status == NodeRunStatus.COMPLETED;
    }

    public boolean isFailed() {
        return status == NodeRunStatus.FAILED;
    }
}
