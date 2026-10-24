package com.codemind.review.domain.task;

import java.util.Optional;

/**
 * 上一次成功发布的评审基准端口（M10）。回写成功后才写入/更新，不在任务创建或评审完成时写
 * （避免把失败/卡住的任务当成基准，漏评已改未评的代码——见 {@code docs/plans/M10.md} D6）。
 */
public interface LastReviewedHeadRepository {

    Optional<LastReviewedHead> findFor(String repo, int prNumber);

    /** 覆盖写：同一 {@code (repo, prNumber)} 再次调用用新值覆盖旧值，不插出第二行。 */
    void upsert(String repo, int prNumber, String baseSha, String headSha);
}
