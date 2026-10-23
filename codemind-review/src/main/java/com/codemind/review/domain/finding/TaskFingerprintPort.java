package com.codemind.review.domain.finding;

import java.util.List;
import java.util.Set;

/**
 * 已发布 Finding 指纹的出站端口（M10，memory L1 任务记忆，M8 已建端点）。
 *
 * <p>纯接口，domain 不依赖 Spring Cloud / Feign。
 */
public interface TaskFingerprintPort {

    /** @throws TaskFingerprintException 查询失败（网络、memory 服务不可达） */
    Set<String> published(String repo, int prNumber);

    /** @throws TaskFingerprintException 登记失败 */
    void record(String repo, int prNumber, List<String> fingerprints);
}
