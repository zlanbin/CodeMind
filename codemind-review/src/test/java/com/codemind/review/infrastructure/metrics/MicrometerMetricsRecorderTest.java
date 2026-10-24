package com.codemind.review.infrastructure.metrics;

import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link MicrometerMetricsRecorder} 单测（ARCHITECTURE M12 可观测性）：验证指标名与标签
 * 符合 Task 4 Grafana dashboard 查询约定（{@code codemind_dag_node_duration}，
 * {@code node_type} / {@code success} 标签）。
 */
class MicrometerMetricsRecorderTest {

    @Test
    void recordsTimerTaggedByNodeTypeAndSuccess() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        MicrometerMetricsRecorder recorder = new MicrometerMetricsRecorder(registry);

        recorder.recordNodeExecution("REVIEW_CHUNK", 1234L, true);

        Timer timer = registry.find("codemind_dag_node_duration")
                .tag("node_type", "REVIEW_CHUNK").tag("success", "true").timer();
        assertThat(timer).isNotNull();
        assertThat(timer.count()).isEqualTo(1);
    }
}
