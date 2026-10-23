package com.codemind.memory.domain.memory;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * 归一化后的记忆正文与其 SHA-256。
 *
 * <p>归一化只去首尾空白、把连续空白压成一个空格，不改大小写：代码标识符大小写有语义。
 * {@code (project, kind, hash)} 是写入去重键，同一条记忆反复写入只强化、不重复。
 */
public record MemoryContent(String text, String hash) {

    /** 记忆是一条规则或模式，不是文档；过长既拖慢 pg_trgm，也会原样挤进 prompt。 */
    public static final int MAX_LENGTH = 2000;

    public static MemoryContent of(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("记忆内容不能为空");
        }
        String text = raw.strip().replaceAll("\\s+", " ");
        if (text.length() > MAX_LENGTH) {
            throw new IllegalArgumentException("记忆内容不能超过 " + MAX_LENGTH + " 字符");
        }
        return new MemoryContent(text, sha256(text));
    }

    private static String sha256(String text) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JDK 缺少 SHA-256", e);
        }
    }
}
