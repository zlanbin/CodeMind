package com.codemind.review.domain.finding;

import java.util.Objects;

/**
 * 评审意见（ARCHITECTURE 4）。单个 Agent 对一个 chunk 产出若干 Finding。
 *
 * <p>纯值对象，不依赖 Spring。{@code fingerprint} 由 {@link Fingerprints} 计算，
 * 用于多 Agent 去重（M5）与增量评审判重（M10）。
 *
 * @param file       文件路径（相对仓库根）
 * @param line       行号（新文件侧；0 表示文件级意见，无具体行）
 * @param severity   严重级别
 * @param category   评审维度
 * @param message    问题描述
 * @param suggestion 修改建议（可为空串）
 * @param fingerprint 去重指纹
 */
public record Finding(
        String file,
        int line,
        Severity severity,
        Category category,
        String message,
        String suggestion,
        String fingerprint) {

    public Finding {
        file = requireText(file, "file");
        severity = Objects.requireNonNull(severity, "severity");
        category = Objects.requireNonNull(category, "category");
        message = requireText(message, "message");
        suggestion = suggestion == null ? "" : suggestion;
        fingerprint = requireText(fingerprint, "fingerprint");
        if (line < 0) {
            throw new IllegalArgumentException("line 不能为负，实际 " + line);
        }
    }

    /**
     * 工厂：由解析出的原始字段构造，自动计算 fingerprint。
     * {@code codeSnippet} 用于指纹归一化，通常传问题所在行的代码或 message。
     */
    public static Finding of(String file, int line, Severity severity, Category category,
                             String message, String suggestion, String codeSnippet) {
        String fp = Fingerprints.of(file, category, codeSnippet);
        return new Finding(file, line, severity, category, message, suggestion, fp);
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " 不能为空");
        }
        return value;
    }
}
