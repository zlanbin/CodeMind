package com.codemind.review.domain.chunk;

/**
 * 估算一段文本的 token 数。领域服务接口，便于单测替换为可预测的桩实现。
 *
 * <p>默认实现 {@link JtokkitTokenEstimator} 用 jtokkit 的 BPE 分词器精确计数。
 */
public interface TokenEstimator {

    int estimate(String text);
}
