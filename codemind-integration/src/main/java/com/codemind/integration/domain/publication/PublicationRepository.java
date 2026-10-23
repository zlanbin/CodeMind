package com.codemind.integration.domain.publication;

import com.codemind.common.event.ReviewCompleted;

import java.util.Optional;

/** Atomic durable transitions shared by all integration instances. */
public interface PublicationRepository {

    /** Keeps the first event; throws IllegalStateException for a conflicting business payload. */
    PublicationRecord register(ReviewCompleted event);

    Optional<PublicationRecord> findByTaskId(String taskId);

    /** READY plus the expected mode is the only authorization to start an attempt. */
    boolean beginAttempt(String taskId, String token, PublicationMode expectedMode);

    boolean markUncertain(String taskId, String token, String reason);

    /** Only a definitive rejection of the matching IN_FLIGHT attempt can restore READY. */
    boolean recordRejection(String taskId, String token, PublicationMode nextMode, String reason);

    /** Accepts matching IN_FLIGHT/UNCERTAIN attempts; never overwrites PUBLISHED. */
    boolean recordSuccess(String taskId, String token, PublicationResult result);

    /** Caller has read a complete SCM marker. Confirms success, never authorizes a resend. */
    boolean reconcileSuccess(String taskId, PublicationResult result);
}
