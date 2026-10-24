package com.codemind.review.domain.dag;

/**
 * 节点执行耗时指标端口（ARCHITECTURE M12 可观测性）。
 *
 * <p>domain 层只声明记录动作，不关心具体指标系统（Micrometer / 其他）。
 * 实现放在 infrastructure 层。
 */
public interface MetricsRecorder {

    /**
     * 记录一次节点执行。
     *
     * @param nodeType      节点类型（{@link Node#nodeType()}）
     * @param durationMillis 本次执行耗时（毫秒）
     * @param success       本次执行是否成功
     */
    void recordNodeExecution(String nodeType, long durationMillis, boolean success);
}
