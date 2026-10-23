package com.codemind.review.domain.publication;

import com.codemind.common.event.ReviewCompleted;

import java.time.Instant;

public record OutboxEntry(ReviewCompleted event, Instant createdAt, Instant nextAttemptAt,
                          int attempts, Instant acknowledgedAt) { }
