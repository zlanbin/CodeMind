package com.codemind.memory.domain.memory;

/** 只有 ACTIVE 参与召回；ARCHIVED 由遗忘策略转入，再次写入同内容会复活。 */
public enum MemoryStatus {
    ACTIVE,
    ARCHIVED
}
