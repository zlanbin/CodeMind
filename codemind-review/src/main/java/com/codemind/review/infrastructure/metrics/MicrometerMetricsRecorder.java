package com.codemind.review.infrastructure.metrics;

import com.codemind.review.domain.dag.MetricsRecorder;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import java.util.concurrent.TimeUnit;

/**
 * {@link MetricsRecorder} 的 Micrometer 实现（ARCHITECTURE M12 可观测性）。
 *
 * <p>指标名 {@code codemind_dag_node_duration}，标签 {@code node_type} / {@code success}——
 * Task 4 的 Grafana dashboard 直接查询这个名字，改动需同步更新 dashboard 查询。
 *
 * <p>M12 压测裁决修复：{@code meterRegistry.timer(...)} 便捷方法注册的 Timer 默认不发布
 * percentile histogram，Prometheus 端看不到 {@code _bucket} 系列，dashboard 的 P95 面板永远查空。
 * 改用 {@link Timer.Builder} 并调 {@code publishPercentileHistogram()}，让同一个 Timer 在
 * 注册时额外发布 histogram bucket；指标名、tag 名不变。
 */
public class MicrometerMetricsRecorder implements MetricsRecorder {

    private static final String DURATION_METRIC = "codemind_dag_node_duration";

    private final MeterRegistry meterRegistry;

    public MicrometerMetricsRecorder(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    @Override
    public void recordNodeExecution(String nodeType, long durationMillis, boolean success) {
        Timer.builder(DURATION_METRIC)
                .tag("node_type", nodeType)
                .tag("success", String.valueOf(success))
                .publishPercentileHistogram()
                .register(meterRegistry)
                .record(durationMillis, TimeUnit.MILLISECONDS);
    }
}
