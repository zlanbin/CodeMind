package com.codemind.review.infrastructure.config;

import com.codemind.review.application.FingerprintRegistrationDispatcher;
import com.codemind.review.domain.finding.TaskFingerprintPort;
import com.codemind.review.domain.publication.FingerprintRegistrationOutboxRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

import java.time.Clock;
import java.time.Duration;

@Configuration
public class FingerprintSchedulingConfig {

    @Bean
    public FingerprintRegistrationDispatcher fingerprintRegistrationDispatcher(
            FingerprintRegistrationOutboxRepository repository, TaskFingerprintPort port,
            @Value("${codemind.fingerprint.batch-size:50}") int batchSize,
            @Value("${codemind.fingerprint.lease:120s}") Duration lease,
            @Value("${codemind.fingerprint.retry-delay:60s}") Duration retryDelay) {
        return new FingerprintRegistrationDispatcher(repository, port, Clock.systemUTC(), batchSize, lease, retryDelay);
    }

    @Configuration
    @EnableScheduling
    @ConditionalOnProperty(name = "codemind.fingerprint.dispatch-enabled", havingValue = "true", matchIfMissing = true)
    static class Scheduling {
        private final FingerprintRegistrationDispatcher dispatcher;

        Scheduling(FingerprintRegistrationDispatcher dispatcher) {
            this.dispatcher = dispatcher;
        }

        @Scheduled(fixedDelayString = "${codemind.fingerprint.scan-interval:30s}")
        void dispatch() {
            dispatcher.dispatchDue();
        }
    }
}
