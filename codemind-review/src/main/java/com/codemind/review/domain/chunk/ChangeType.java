package com.codemind.review.domain.chunk;

/** 文件在一次 PR 中的变更类型。 */
public enum ChangeType {
    ADDED,
    MODIFIED,
    DELETED,
    RENAMED
}
