package com.codemind.review.application;

import com.codemind.review.domain.task.ReviewTaskRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.Objects;

/**
 * 卡死任务恢复（M7a）。
 *
 * <p>进程在评审中途重启后，任务停在 {@code PLANNING / REVIEWING / AGGREGATING} 永不推进。
 * checkpoint 已在 {@code node_run} 表里、{@code DagScheduler} 本身支持续跑，缺的只是「谁来触发」。
 *
 * <p>恢复时先把任务标 {@code FAILED} 再重新提交：{@link ReviewExecutionService#executeReview}
 * 只接 {@code CREATED / FAILED}，而 {@code FAILED → PLANNING} 是状态机允许的流转。进程确实死了，
 * 标 FAILED 是事实陈述。重跑时已完成节点从 checkpoint 加载，<b>不会重复调 LLM</b>。
 *
 * <p>定时触发由 {@code RecoverySchedulingConfig} 按开关装配；本服务始终是 bean，测试可直接调。
 */
@Service
public class StaleTaskRecoveryService {

    private static final Logger log = LoggerFactory.getLogger(StaleTaskRecoveryService.class);

    /** 提交评审的抽象，便于单测不建线程池。生产实现是 {@link ReviewExecutionService#submitAsync}。 */
    public interface ReviewSubmitter {
        void submit(String taskId);
    }

    private final ReviewTaskRepository taskRepository;
    private final ReviewSubmitter submitter;
    private final Duration idleThreshold;

    public StaleTaskRecoveryService(ReviewTaskRepository taskRepository,
                                    ReviewSubmitter submitter,
                                    @Value("${codemind.recovery.idle-threshold:15m}") Duration idleThreshold) {
        this.taskRepository = Objects.requireNonNull(taskRepository, "taskRepository");
        this.submitter = Objects.requireNonNull(submitter, "submitter");
        this.idleThreshold = Objects.requireNonNull(idleThreshold, "idleThreshold");
    }

    /**
     * 扫一轮卡死任务并重新提交。
     *
     * @return 成功重新提交的任务数
     */
    public int recoverStaleTasks() {
        List<String> stale = taskRepository.findStaleInProgress(idleThreshold);
        if (stale.isEmpty()) {
            return 0;
        }
        log.info("发现 {} 个疑似卡死的进行中任务（空闲超过 {}）", stale.size(), idleThreshold);

        int recovered = 0;
        for (String taskId : stale) {
            if (!taskRepository.claimForRecovery(taskId)) {
                log.debug("任务 {} 已被其它实例抢占或状态已变，跳过", taskId);
                continue;
            }
            try {
                submitter.submit(taskId);
                recovered++;
                log.info("已重新提交卡死任务 taskId={}（将从 checkpoint 续跑）", taskId);
            } catch (RuntimeException e) {
                // 一个坏任务不能卡住整轮扫描：它已标 FAILED，下一轮还会被捞到
                log.error("重新提交卡死任务失败 taskId={}", taskId, e);
            }
        }
        return recovered;
    }
}
