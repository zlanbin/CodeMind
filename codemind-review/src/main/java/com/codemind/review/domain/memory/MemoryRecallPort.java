package com.codemind.review.domain.memory;

import java.util.List;

/**
 * 召回被否决记忆的出站端口（M9b）。
 *
 * <p>review 不直连 memory：召回是跨服务调用，本端口的实现经 Feign 调 memory 的 internal 端点。
 *
 * <p>纯接口，domain 不依赖 Spring Cloud / Feign。
 */
public interface MemoryRecallPort {

    /**
     * 召回某项目下 REJECTED_PATTERN 记忆中与 query 最相关的候选。
     *
     * @throws MemoryRecallException 调用失败（网络、memory 服务不可达）
     */
    List<RecalledMemory> recallRejectedPatterns(String project, String query, int topK, double minScore);
}
