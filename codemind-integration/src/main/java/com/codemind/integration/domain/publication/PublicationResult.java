package com.codemind.integration.domain.publication;

/** Actual SCM result, not the number of comments originally requested. */
public record PublicationResult(long reviewId, int inlineCount, boolean degraded) {

    public PublicationResult {
        if (inlineCount < 0) {
            throw new IllegalArgumentException("inlineCount must not be negative");
        }
    }
}
