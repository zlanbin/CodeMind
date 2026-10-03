package com.codemind.review.application;

import com.codemind.common.event.PullRequestReceived;
import com.codemind.review.domain.task.ReviewTask;
import com.codemind.review.domain.task.ReviewTaskRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * 评审任务用例编排。M1 只建任务到 CREATED，不触发评审（DAG 在 M3 接上）。
 */
@Service
public class ReviewTaskApplicationService {

    private static final Logger log = LoggerFactory.getLogger(ReviewTaskApplicationService.class);

    private final ReviewTaskRepository reviewTaskRepository;

    public ReviewTaskApplicationService(ReviewTaskRepository reviewTaskRepository) {
        this.reviewTaskRepository = reviewTaskRepository;
    }

    /**
     * 处理 PullRequestReceived，幂等：同 (repo, prNumber, headSha) 重复投递只建一个任务。
     *
     * @return 新建的任务；已存在时返回已有任务
     */
    @Transactional
    public ReviewTask createTaskFrom(PullRequestReceived event) {
        ReviewTask task = ReviewTask.create(event.repo(), event.prNumber(),
                event.headSha(), event.baseSha(), event.eventId());

        if (reviewTaskRepository.insertIfAbsent(task)) {
            log.info("已建评审任务 taskId={} repo={} pr={} headSha={} eventId={}",
                    task.taskId(), task.repo(), task.prNumber(), task.headSha(), event.eventId());
            return task;
        }

        // 唯一索引拦住了：webhook 重放且 Redis 幂等键已过期，或 MQ 重复投递。
        Optional<ReviewTask> existing = reviewTaskRepository
                .findByPrHead(event.repo(), event.prNumber(), event.headSha());
        log.info("评审任务已存在，跳过 repo={} pr={} headSha={} existingTaskId={} eventId={}",
                event.repo(), event.prNumber(), event.headSha(),
                existing.map(ReviewTask::taskId).orElse("<查询不到>"), event.eventId());
        return existing.orElse(task);
    }
}
