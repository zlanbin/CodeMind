package com.codemind.integration.domain.publication;

/** Durable publication fence: neither IN_FLIGHT nor UNCERTAIN authorizes another send. */
public enum PublicationState {
    READY, IN_FLIGHT, UNCERTAIN, PUBLISHED
}
