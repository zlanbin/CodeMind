package com.codemind.review.domain.task;

import java.util.Optional;

/**
 * 评审任务仓储接口（出站端口），实现在 infrastructure 层。
 */
public interface ReviewTaskRepository {

    /**
     * 插入任务，依赖 {@code (repo, pr_number, head_sha)} 唯一索引做幂等兜底。
     *
     * @return true 表示插入成功，false 表示已存在同 (repo, prNumber, headSha) 的任务
     */
    boolean insertIfAbsent(ReviewTask task);

    Optional<ReviewTask> findByPrHead(String repo, int prNumber, String headSha);

    /**
     * 更新任务状态，同时刷新 updated_at（M3 状态流转落库）。
     */
    void updateStatus(String taskId, ReviewTaskStatus status);
}
