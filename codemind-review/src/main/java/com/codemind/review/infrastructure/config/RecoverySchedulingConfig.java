package com.codemind.review.infrastructure.config;

import com.codemind.review.application.ReviewExecutionService;
import com.codemind.review.application.StaleTaskRecoveryService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * 卡死任务恢复的定时触发（M7a）。
 *
 * <p>默认关（{@code codemind.recovery.enabled=false}）：开着会让所有现有 IT 的上下文里
 * 多一个定时任务在扫库。恢复逻辑本身由直接调 {@code recoverStaleTasks()} 的测试覆盖，
 * 不依赖定时触发。<b>真实运行与演示要设 {@code CODEMIND_RECOVERY_ENABLED=true}。</b>
 */
@Configuration
@EnableScheduling
public class RecoverySchedulingConfig {

    /**
     * 生产实现：提交到 review 的虚拟线程池。
     *
     * <p>无条件装配：{@link StaleTaskRecoveryService} 始终是 bean（测试要直接调它），
     * 它依赖本 bean，若本 bean 带条件则开关关闭时整个上下文起不来。
     */
    @Bean
    public StaleTaskRecoveryService.ReviewSubmitter reviewSubmitter(
            ReviewExecutionService reviewExecutionService) {
        return reviewExecutionService::submitAsync;
    }

    /** 只有定时扫描带开关，恢复能力本身始终可用。 */
    @Bean
    @ConditionalOnProperty(name = "codemind.recovery.enabled", havingValue = "true")
    public RecoveryScanner recoveryScanner(StaleTaskRecoveryService recoveryService) {
        return new RecoveryScanner(recoveryService);
    }

    /** 定时扫描器。单独一个类，便于 @Scheduled 只在开关打开时生效。 */
    public static class RecoveryScanner {

        private final StaleTaskRecoveryService recoveryService;

        public RecoveryScanner(StaleTaskRecoveryService recoveryService) {
            this.recoveryService = recoveryService;
        }

        @Scheduled(fixedDelayString = "${codemind.recovery.scan-interval:5m}")
        public void scan() {
            recoveryService.recoverStaleTasks();
        }
    }
}
