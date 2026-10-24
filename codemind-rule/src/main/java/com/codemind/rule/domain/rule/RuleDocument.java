package com.codemind.rule.domain.rule;

import java.time.Instant;
import java.util.Objects;

/** 规范文档聚合根（M11）。 */
public record RuleDocument(long id, String project, String title, RuleDocumentStatus status,
                           int version, Instant uploadedAt) {

    public RuleDocument {
        Objects.requireNonNull(project, "project");
        Objects.requireNonNull(title, "title");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(uploadedAt, "uploadedAt");
        if (version <= 0) {
            throw new IllegalArgumentException("version 必须 > 0：" + version);
        }
    }
}
