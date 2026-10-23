package com.codemind.integration.application.port;

/** A renewable publication-lock lease owned by one publication attempt. */
public interface PublicationLease extends AutoCloseable {

    /**
     * Synchronously verifies that Redis still contains this lease's token.
     * Once verification fails, this lease remains invalid for its lifetime.
     */
    boolean isHeld();

    @Override
    void close();
}
