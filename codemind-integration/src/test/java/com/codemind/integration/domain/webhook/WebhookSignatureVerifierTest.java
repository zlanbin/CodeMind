package com.codemind.integration.domain.webhook;

import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WebhookSignatureVerifierTest {

    private static final String SECRET = "test-secret";
    private static final byte[] BODY = "{\"action\":\"opened\"}".getBytes(StandardCharsets.UTF_8);

    private final WebhookSignatureVerifier verifier = new WebhookSignatureVerifier(SECRET);

    @Test
    void 签名正确时通过() {
        assertThat(verifier.verify(sign(SECRET, BODY), BODY)).isTrue();
    }

    @Test
    void 密钥不同时失败() {
        assertThat(verifier.verify(sign("wrong-secret", BODY), BODY)).isFalse();
    }

    @Test
    void 请求体被改动时失败() {
        byte[] tampered = "{\"action\":\"closed\"}".getBytes(StandardCharsets.UTF_8);
        assertThat(verifier.verify(sign(SECRET, BODY), tampered)).isFalse();
    }

    @Test
    void 签名头缺失或格式错误时失败() {
        assertThat(verifier.verify(null, BODY)).isFalse();
        assertThat(verifier.verify("", BODY)).isFalse();
        // 缺 sha256= 前缀
        assertThat(verifier.verify(sign(SECRET, BODY).substring(7), BODY)).isFalse();
        assertThat(verifier.verify("sha1=abc", BODY)).isFalse();
    }

    @Test
    void 空密钥构造时报错() {
        assertThatThrownBy(() -> new WebhookSignatureVerifier(" "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** 按 GitHub 的算法算出期望签名头。 */
    private static String sign(String secret, byte[] body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return "sha256=" + HexFormat.of().formatHex(mac.doFinal(body));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
