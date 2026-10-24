package com.codemind.review.domain.rule;

/** 召回的规则条款（M11）。{@code documentTitle} 供 prompt 渲染引用来源（《文档标题》sectionPath）。 */
public record RecalledRule(String documentTitle, String sectionPath, String heading, String content,
                           double similarity) {
}
