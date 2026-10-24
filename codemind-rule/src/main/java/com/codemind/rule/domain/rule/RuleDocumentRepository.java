package com.codemind.rule.domain.rule;

import java.time.Instant;

/** 规范文档仓储端口（M11）。 */
public interface RuleDocumentRepository {

    /** 插入新文档（version/status 由调用方算好传入），返回带真实 id 的文档。 */
    RuleDocument insert(String project, String title, int version, Instant uploadedAt);

    /**
     * 把该 project+title 现有的 ACTIVE 文档转 ARCHIVED（版本替换，M11 D7）。
     *
     * @return 若存在旧 ACTIVE 版本则返回其 version，否则返回空（供调用方算出新 version = 旧 + 1）
     */
    java.util.Optional<Integer> archiveActiveByProjectAndTitle(String project, String title);
}
