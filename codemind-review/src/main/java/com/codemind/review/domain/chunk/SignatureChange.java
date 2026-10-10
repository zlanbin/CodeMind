package com.codemind.review.domain.chunk;

import java.util.Objects;

/**
 * 一处方法签名变更（M6）。由 {@link ChangedSignatureExtractor} 从 AST + diff 删除行确定性提取，
 * <b>不依赖 LLM</b>，作为 ChangeMap 的输入之一（ARCHITECTURE 6.2「方法签名变更」）。
 *
 * <p>{@link Kind#SIGNATURE_CHANGED} 是 M6 验证点的关键信号：调用方与定义方的参数类型不一致时，
 * GlobalReduce 据此判断跨文件问题，而不必依赖 LLM 在摘要里把参数类型写全。
 *
 * @param file       文件路径（新侧）
 * @param signature  变更后的方法限定名，形如 {@code OrderService#cancel(int)}；
 *                   {@link Kind#REMOVED} 时为被删除的签名
 * @param previous   变更前的方法限定名；仅 {@link Kind#SIGNATURE_CHANGED} 非空
 * @param kind       变更类型
 */
public record SignatureChange(String file, String signature, String previous, Kind kind) {

    public SignatureChange {
        file = requireText(file, "file");
        signature = requireText(signature, "signature");
        kind = Objects.requireNonNull(kind, "kind");
        if (kind == Kind.SIGNATURE_CHANGED && (previous == null || previous.isBlank())) {
            throw new IllegalArgumentException("SIGNATURE_CHANGED 必须给出 previous");
        }
    }

    public enum Kind {
        /** 新增方法（diff 中无同名方法的删除行）。 */
        ADDED,
        /** 方法被删除（删除行里有声明，新侧 AST 中已无同名方法）。 */
        REMOVED,
        /** 同名方法的参数列表发生变化（含参数类型变化）。 */
        SIGNATURE_CHANGED,
        /** 签名未变，只改了方法体。 */
        BODY_CHANGED
    }

    /** 人类可读的一行描述，用于投喂 LLM 的 prompt。 */
    public String describe() {
        return switch (kind) {
            case ADDED -> "%s 新增 %s".formatted(file, signature);
            case REMOVED -> "%s 删除 %s".formatted(file, signature);
            case SIGNATURE_CHANGED -> "%s 签名变更 %s → %s".formatted(file, previous, signature);
            case BODY_CHANGED -> "%s 方法体变更 %s".formatted(file, signature);
        };
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " 不能为空");
        }
        return value;
    }
}
