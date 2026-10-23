package com.codemind.integration.application.port;

import java.util.List;

/** One review pinned to a commit, with a summary and zero or more inline comments. */
public record ReviewDraft(String commitId, String body, List<DraftComment> comments) {}
