package com.codemind.integration.domain.publication;

import com.codemind.common.event.ReviewCompleted;

import java.time.Instant;

/** Immutable snapshot; event always holds the first registered request, including its transport metadata. */
public record PublicationRecord(
        String taskId,
        ReviewCompleted event,
        String contentDigest,
        PublicationState state,
        PublicationMode mode,
        String attemptToken,
        PublicationResult result,
        Instant createdAt,
        Instant updatedAt,
        String lastError
) {
}
