package com.codemind.review.infrastructure.config;

import com.codemind.review.application.ReviewOutboxDispatcher;
import com.codemind.review.domain.event.DomainEventPublisher;
import com.codemind.review.domain.publication.ReviewOutboxRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

import java.time.Clock;
import java.time.Duration;

@Configuration
public class PublicationSchedulingConfig {
    @Bean
    public ReviewOutboxDispatcher reviewOutboxDispatcher(ReviewOutboxRepository repository,
            DomainEventPublisher publisher,
            @Value("${codemind.publication.batch-size:50}") int batchSize,
            @Value("${codemind.publication.lease:120s}") Duration lease,
            @Value("${codemind.publication.retry-delay:60s}") Duration retryDelay,
            @Value("${codemind.publication.max-attempts:20}") int maxAttempts) {
        return new ReviewOutboxDispatcher(repository, publisher, Clock.systemUTC(), batchSize, lease, retryDelay, maxAttempts);
    }

    @Configuration
    @EnableScheduling
    @ConditionalOnProperty(name = "codemind.publication.dispatch-enabled", havingValue = "true", matchIfMissing = true)
    static class Scheduling {
        private final ReviewOutboxDispatcher dispatcher;
        Scheduling(ReviewOutboxDispatcher dispatcher) { this.dispatcher = dispatcher; }
        @Scheduled(fixedDelayString = "${codemind.publication.scan-interval:30s}")
        void dispatch() { dispatcher.dispatchDue(); }
    }
}
