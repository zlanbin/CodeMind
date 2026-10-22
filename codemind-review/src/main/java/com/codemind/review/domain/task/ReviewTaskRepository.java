package com.codemind.review.domain.task;

import java.time.Duration;
import java.util.List;
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
     * 按 taskId 重建聚合（M7a）。异步执行评审时手上只有 taskId，必须能按它取回任务。
     */
    Optional<ReviewTask> findById(String taskId);

    /**
     * 更新任务状态，同时刷新 updated_at（M3 状态流转落库）。
     */
    void updateStatus(String taskId, ReviewTaskStatus status);

    /**
     * 找出卡死的进行中任务（M7a）：进程在评审中途重启后，任务会停在
     * {@code PLANNING / REVIEWING / AGGREGATING} 永不推进。
     *
     * <p>判定「卡死」要同时看两个时间：任务自身的 {@code updated_at}（只在阶段切换时刷新），
     * 和该任务名下节点的最后活动时间。只看前者会把正常跑的任务误判成卡死——
     * {@code REVIEWING} 阶段几十个 LLM 调用期间任务状态根本不变。
     *
     * @param idleFor 空闲多久算卡死
     * @return 卡死任务的 taskId
     */
    List<String> findStaleInProgress(Duration idleFor);

    /**
     * 抢占一个卡死任务用于恢复（M7a）：把它从进行中状态改为 {@code FAILED}，
     * 以便走 {@code FAILED → PLANNING} 的续跑流转。
     *
     * <p>单条 SQL 原子操作，多实例同时扫只有一个抢到。
     *
     * @return true 表示抢到，false 表示状态已被别人改掉
     */
    boolean claimForRecovery(String taskId);
}
