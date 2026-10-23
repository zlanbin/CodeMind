package com.codemind.integration.application.port;

/** A submitted SCM review; adapters normalize a null body to an empty string. */
public record ExistingReview(long id, String body) {}
