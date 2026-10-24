package com.codemind.review.infrastructure.metrics;

import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import io.micrometer.prometheusmetrics.PrometheusConfig;
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

    /**
     * M12 压测裁决修复：验证 Timer 真的发布了 percentile histogram，Prometheus 端能看到
     * {@code _bucket} 系列（Grafana P95 面板依赖的那个系列）。直接用
     * {@link PrometheusMeterRegistry} 渲染出 Prometheus exposition 格式文本，断言里面包含
     * {@code _bucket}，比只检查 Timer 的 histogram 配置更接近真实验证目标。
     */
    @Test
    void publishesHistogramBucketsForPrometheusScrape() {
        PrometheusMeterRegistry registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        MicrometerMetricsRecorder recorder = new MicrometerMetricsRecorder(registry);

        recorder.recordNodeExecution("REVIEW_CHUNK", 1234L, true);

        String scraped = registry.scrape();
        assertThat(scraped).contains("codemind_dag_node_duration_seconds_bucket");
    }
}
