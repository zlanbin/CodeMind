package com.codemind.review.application;

import com.codemind.common.event.ReviewCompleted;
import com.codemind.review.domain.publication.ReviewOutboxRepository;
import com.codemind.review.domain.task.ReviewTask;
import com.codemind.review.domain.task.ReviewTaskRepository;
import com.codemind.review.domain.task.ReviewTaskStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/** 锁 task 后交接原始发布请求，保存成功不等于 GitHub 已发布。 */
@Service
public class ReviewCompletionService {
    private final ReviewTaskRepository tasks;
    private final ReviewOutboxRepository outbox;
    private final TransactionTemplate transactions;

    public ReviewCompletionService(ReviewTaskRepository tasks, ReviewOutboxRepository outbox,
                                   TransactionTemplate transactions) {
        this.tasks = tasks;
        this.outbox = outbox;
        this.transactions = transactions;
    }

    public ReviewCompleted register(ReviewCompleted event) {
        return transactions.execute(status -> {
            ReviewTask task = tasks.findByIdForUpdate(event.taskId())
                    .orElseThrow(() -> new IllegalStateException("完成交接时任务不存在: " + event.taskId()));
            var existing = outbox.findByTaskId(event.taskId());
            if (existing.isPresent()) return existing.get().event();
            if (task.status() != ReviewTaskStatus.AGGREGATING) {
                throw new IllegalStateException("任务尚未完成归约: " + event.taskId());
            }
            if (!task.repo().equals(event.repo()) || task.prNumber() != event.prNumber()
                    || !task.headSha().equals(event.headSha())) {
                throw new IllegalArgumentException("发布请求与任务身份不一致");
            }
            outbox.insert(event);
            tasks.updateStatus(task.taskId(), ReviewTaskStatus.AGGREGATING);
            return event;
        });
    }
}
