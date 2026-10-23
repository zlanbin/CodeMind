package com.codemind.integration.application.port;

import java.util.Optional;

/** Application port for the per-task publication lock. */
public interface PublicationLock {

    Optional<PublicationLease> tryAcquire(String taskId);
}
