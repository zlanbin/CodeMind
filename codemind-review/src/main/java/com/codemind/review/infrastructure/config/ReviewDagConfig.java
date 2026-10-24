package com.codemind.review.infrastructure.config;

import com.codemind.review.domain.agent.GlobalReduceAgent;
import com.codemind.review.domain.agent.ReviewAgent;
import com.codemind.review.domain.agent.ReviewDimension;
import com.codemind.review.domain.chunk.ChunkBudget;
import com.codemind.review.domain.chunk.JtokkitTokenEstimator;
import com.codemind.review.domain.chunk.SemanticChunker;
import com.codemind.review.domain.chunk.TokenEstimator;
import com.codemind.review.domain.dag.DagOrchestrator;
import com.codemind.review.domain.dag.DagScheduler;
import com.codemind.review.domain.dag.MetricsRecorder;
import com.codemind.review.domain.dag.NodeRunRepository;
import com.codemind.review.domain.rule.RuleRecallPort;
import com.codemind.review.domain.task.ReviewTaskRepository;
import com.codemind.review.infrastructure.metrics.MicrometerMetricsRecorder;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * DAG 引擎与切片器的装配（M7a）。
 *
 * <p>M3~M6 把这些 domain 服务都写好了，但只在测试里 {@code new}，生产路径上没有 bean，
 * 评审从来跑不起来。本配置是那条缺失的线。
 */
@Configuration
public class ReviewDagConfig {

    @Bean
    public TokenEstimator tokenEstimator() {
        return new JtokkitTokenEstimator();
    }

    @Bean
    public SemanticChunker semanticChunker(TokenEstimator tokenEstimator) {
        return new SemanticChunker(tokenEstimator);
    }

    /**
     * @param diffBudget 单片 diff 的 token 上限；0 表示用默认（总预算 128k × 30%）
     */
    @Bean
    public ChunkBudget chunkBudget(@Value("${codemind.dag.diff-budget:0}") int diffBudget) {
        return diffBudget > 0 ? ChunkBudget.ofDiffBudget(diffBudget) : ChunkBudget.ofDefault();
    }

    @Bean
    public MetricsRecorder metricsRecorder(MeterRegistry meterRegistry) {
        return new MicrometerMetricsRecorder(meterRegistry);
    }

    @Bean
    public DagScheduler dagScheduler(NodeRunRepository nodeRunRepository,
                                     MetricsRecorder metricsRecorder,
                                     @Value("${codemind.dag.node-timeout:120s}") Duration nodeTimeout,
                                     @Value("${codemind.dag.node-retries:1}") int nodeRetries) {
        return new DagScheduler(nodeRunRepository, metricsRecorder, nodeTimeout, nodeRetries);
    }

    @Bean
    public DagOrchestrator dagOrchestrator(DagScheduler dagScheduler,
                                           Map<ReviewDimension, ReviewAgent> reviewAgents,
                                           GlobalReduceAgent globalReduceAgent,
                                           ReviewTaskRepository taskRepository,
                                           RuleRecallPort ruleRecallPort) {
        return new DagOrchestrator(dagScheduler, reviewAgents, globalReduceAgent, taskRepository, ruleRecallPort);
    }

    /**
     * 评审执行线程池。虚拟线程：评审任务绝大部分时间在等 LLM 响应，不占 CPU。
     *
     * <p>MQ 消费线程不能被几十秒的 LLM 调用阻塞（会触发消息重投），所以建完任务就提交到这里。
     */
    @Bean(destroyMethod = "shutdown")
    public ExecutorService reviewExecutor() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }
}
