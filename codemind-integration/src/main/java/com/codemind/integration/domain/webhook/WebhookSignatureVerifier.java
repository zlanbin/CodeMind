package com.codemind.integration.domain.webhook;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.HexFormat;

/**
 * GitHub webhook 验签：HMAC-SHA256(secret, rawBody)，与 {@code X-Hub-Signature-256} 头比对。
 *
 * <p>比对走 {@link MessageDigest#isEqual} 的常量时间实现，避免逐字节比较泄漏时序信息。
 * 必须对<b>原始请求体字节</b>计算，JSON 反序列化再序列化会改变字节内容导致验签失败。
 */
public final class WebhookSignatureVerifier {

    private static final String ALGORITHM = "HmacSHA256";
    private static final String PREFIX = "sha256=";

    private final byte[] secret;

    public WebhookSignatureVerifier(String secret) {
        if (secret == null || secret.isBlank()) {
            throw new IllegalArgumentException("webhook secret 不能为空");
        }
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
    }

    /**
     * @param signatureHeader {@code X-Hub-Signature-256} 头，形如 {@code sha256=<hex>}；缺失时传 null
     * @param rawBody         原始请求体字节
     * @return 验签是否通过
     */
    public boolean verify(String signatureHeader, byte[] rawBody) {
        if (signatureHeader == null || !signatureHeader.startsWith(PREFIX) || rawBody == null) {
            return false;
        }
        byte[] expected = (PREFIX + hmacHex(rawBody)).getBytes(StandardCharsets.UTF_8);
        byte[] actual = signatureHeader.getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(expected, actual);
    }

    private String hmacHex(byte[] rawBody) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(secret, ALGORITHM));
            return HexFormat.of().formatHex(mac.doFinal(rawBody));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HMAC-SHA256 计算失败", e);
        }
    }
}
