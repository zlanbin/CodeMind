package com.codemind.rule.domain.rule;

import java.util.Objects;

/**
 * 规范文档切块（M11）。{@code id} 为 0 表示尚未入库（切块阶段产出的占位值，
 * 由仓储插入后回填真实 id）。
 */
public record RuleChunk(long id, long documentId, String project, String sectionPath,
                        String heading, String content) {

    public RuleChunk {
        Objects.requireNonNull(project, "project");
        Objects.requireNonNull(sectionPath, "sectionPath");
        Objects.requireNonNull(heading, "heading");
        Objects.requireNonNull(content, "content");
    }
}
