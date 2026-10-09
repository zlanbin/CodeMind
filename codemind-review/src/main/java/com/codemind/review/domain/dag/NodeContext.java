package com.codemind.review.domain.dag;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 节点执行上下文：包含任务标识、已完成节点结果、共享状态。
 *
 * <p>不可变，线程安全。
 */
public final class NodeContext {

    private final String taskId;
    private final Map<String, Object> completedResults;
    private final Map<String, Object> sharedState;

    private NodeContext(String taskId, Map<String, Object> completedResults, Map<String, Object> sharedState) {
        this.taskId = Objects.requireNonNull(taskId, "taskId");
        this.completedResults = Map.copyOf(completedResults);
        this.sharedState = Map.copyOf(sharedState);
    }

    public static NodeContext create(String taskId) {
        return new NodeContext(taskId, Map.of(), Map.of());
    }

    /** 别名：创建初始上下文（测试友好）。 */
    public static NodeContext initial(String taskId) {
        return create(taskId);
    }

    /** 添加已完成节点结果，返回新上下文。 */
    public NodeContext withCompletedResult(String nodeId, Object result) {
        Map<String, Object> updated = new HashMap<>(completedResults);
        updated.put(nodeId, result);
        return new NodeContext(taskId, updated, sharedState);
    }

    /** 设置共享状态（如 ChangeMap），返回新上下文。 */
    public NodeContext withSharedState(String key, Object value) {
        Map<String, Object> updated = new HashMap<>(sharedState);
        updated.put(key, value);
        return new NodeContext(taskId, completedResults, updated);
    }

    public String taskId() {
        return taskId;
    }

    public Map<String, Object> completedResults() {
        return completedResults;
    }

    public Map<String, Object> sharedState() {
        return sharedState;
    }

    @SuppressWarnings("unchecked")
    public <T> T getCompletedResult(String nodeId, Class<T> type) {
        Object result = completedResults.get(nodeId);
        if (result == null) {
            return null;
        }
        if (!type.isInstance(result)) {
            throw new IllegalArgumentException(
                    "节点 %s 结果类型不匹配: 期望 %s, 实际 %s".formatted(nodeId, type, result.getClass()));
        }
        return (T) result;
    }

    @SuppressWarnings("unchecked")
    public <T> T getSharedState(String key, Class<T> type) {
        Object value = sharedState.get(key);
        if (value == null) {
            return null;
        }
        if (!type.isInstance(value)) {
            throw new IllegalArgumentException(
                    "共享状态 %s 类型不匹配: 期望 %s, 实际 %s".formatted(key, type, value.getClass()));
        }
        return (T) value;
    }
}
