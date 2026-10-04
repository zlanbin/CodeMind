package com.codemind.review.domain.chunk;

import com.knuddels.jtokkit.Encodings;
import com.knuddels.jtokkit.api.Encoding;
import com.knuddels.jtokkit.api.EncodingType;

/**
 * 用 jtokkit 的 BPE 分词器精确计数 token。默认用 {@code cl100k_base}
 * （GPT-4 / 多数 OpenAI 兼容模型，含 DeepSeek / 通义 的近似）。
 *
 * <p>jtokkit 是纯计算库，不依赖 Spring；放在 domain 层不破坏分层约定。
 * {@code Encoding} 实例线程安全，构造一次即可复用。
 */
public final class JtokkitTokenEstimator implements TokenEstimator {

    private final Encoding encoding;

    public JtokkitTokenEstimator() {
        this(EncodingType.CL100K_BASE);
    }

    public JtokkitTokenEstimator(EncodingType encodingType) {
        this.encoding = Encodings.newLazyEncodingRegistry().getEncoding(encodingType);
    }

    @Override
    public int estimate(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        return encoding.countTokens(text);
    }
}
