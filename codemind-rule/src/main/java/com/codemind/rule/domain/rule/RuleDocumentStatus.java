package com.codemind.rule.domain.rule;

/** 规范文档状态：召回只查 ACTIVE；重新上传同 project+title 时旧版本转 ARCHIVED（M11 D7）。 */
public enum RuleDocumentStatus {
    ACTIVE,
    ARCHIVED
}
