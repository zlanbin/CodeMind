package com.codemind.memory.domain.memory;

/** L2 项目长期记忆的三类内容（ARCHITECTURE 7.1）。 */
public enum MemoryKind {
    /** 团队规范。 */
    CONVENTION,
    /** 被开发者否决过的意见模式（M9 反馈闭环写入）。 */
    REJECTED_PATTERN,
    /** 项目里反复出现的缺陷。 */
    COMMON_DEFECT
}
