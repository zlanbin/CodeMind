package com.codemind.memory.domain.fingerprint;

import java.util.Collection;
import java.util.Set;

/** L1 任务记忆：某个 PR 已发布过的 Finding 指纹集合（M10 增量评审据此去重）。 */
public interface TaskFingerprintRepository {

    /** 已存在的指纹忽略，返回实际新增条数。 */
    int addAll(String repo, int prNumber, Collection<String> fingerprints);

    Set<String> findAll(String repo, int prNumber);
}
