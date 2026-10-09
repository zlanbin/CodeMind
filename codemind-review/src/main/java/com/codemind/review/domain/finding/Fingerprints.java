package com.codemind.review.domain.finding;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Finding 指纹（ARCHITECTURE 4）：{@code fingerprint = hash(file + category + normalize(snippet))}。
 *
 * <p>归一化消除空白与大小写差异，使「同一处同类问题」在多 Agent 命中（M5）或再次 push（M10）时
 * 得到相同指纹，用于去重与增量判重。
 */
public final class Fingerprints {

    private Fingerprints() {
    }

    public static String of(String file, Category category, String snippet) {
        String normalized = normalize(snippet);
        String raw = file + "\u0000" + category.name() + "\u0000" + normalized;
        return sha256(raw);
    }

    /** 去掉所有空白并转小写，消除格式差异带来的指纹抖动。 */
    static String normalize(String snippet) {
        if (snippet == null) {
            return "";
        }
        return snippet.replaceAll("\\s+", "").toLowerCase();
    }

    private static String sha256(String raw) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(raw.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JVM 必须支持 SHA-256", e);
        }
    }
}
