package com.codemind.review.infrastructure.metrics;

import com.codemind.review.domain.dag.MetricsRecorder;
import io.micrometer.core.instrument.MeterRegistry;

import java.util.concurrent.TimeUnit;

/**
 * {@link MetricsRecorder} 的 Micrometer 实现（ARCHITECTURE M12 可观测性）。
 *
 * <p>指标名 {@code codemind_dag_node_duration}，标签 {@code node_type} / {@code success}——
 * Task 4 的 Grafana dashboard 直接查询这个名字，改动需同步更新 dashboard 查询。
 */
public class MicrometerMetricsRecorder implements MetricsRecorder {

    private final MeterRegistry meterRegistry;

    public MicrometerMetricsRecorder(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    @Override
    public void recordNodeExecution(String nodeType, long durationMillis, boolean success) {
        meterRegistry.timer("codemind_dag_node_duration", "node_type", nodeType, "success", String.valueOf(success))
                .record(durationMillis, TimeUnit.MILLISECONDS);
    }
}
