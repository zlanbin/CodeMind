package com.codemind.integration.application.port;

/** An inline comment at a new-side file line; path is a repository-relative, unencoded Git path. */
public record DraftComment(String path, int line, String body) {}
