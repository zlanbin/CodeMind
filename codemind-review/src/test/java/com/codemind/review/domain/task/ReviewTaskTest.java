package com.codemind.review.domain.task;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReviewTaskTest {

    private static ReviewTask newTask() {
        return ReviewTask.create("octocat/hello-world", 42, "head-sha", "base-sha", "event-1");
    }

    @Test
    void 新建任务状态为CREATED且有taskId() {
        ReviewTask task = newTask();
        assertThat(task.status()).isEqualTo(ReviewTaskStatus.CREATED);
        assertThat(task.taskId()).isNotBlank();
        assertThat(task.createdAt()).isNotNull();
    }

    @Test
    void 按主流程逐级流转() {
        ReviewTask task = newTask();
        task.transitionTo(ReviewTaskStatus.PLANNING);
        task.transitionTo(ReviewTaskStatus.REVIEWING);
        task.transitionTo(ReviewTaskStatus.AGGREGATING);
        task.transitionTo(ReviewTaskStatus.PUBLISHED);
        assertThat(task.status().isTerminal()).isTrue();
    }

    @Test
    void 跳级流转被拒绝() {
        ReviewTask task = newTask();
        assertThatThrownBy(() -> task.transitionTo(ReviewTaskStatus.REVIEWING))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("非法状态流转");
        assertThat(task.status()).isEqualTo(ReviewTaskStatus.CREATED);
    }

    @Test
    void 任一进行中阶段都能转FAILED且可从checkpoint回到原阶段() {
        ReviewTask task = newTask();
        task.transitionTo(ReviewTaskStatus.PLANNING);
        task.transitionTo(ReviewTaskStatus.REVIEWING);
        task.transitionTo(ReviewTaskStatus.FAILED);
        task.transitionTo(ReviewTaskStatus.REVIEWING);
        assertThat(task.status()).isEqualTo(ReviewTaskStatus.REVIEWING);
    }

    @Test
    void PUBLISHED是终态不可再流转() {
        assertThat(ReviewTaskStatus.PUBLISHED.allowedNext()).isEmpty();
        assertThat(ReviewTaskStatus.FAILED.canTransitionTo(ReviewTaskStatus.PUBLISHED)).isFalse();
    }

    @Test
    void 非法入参被拒绝() {
        assertThatThrownBy(() -> ReviewTask.create("repo", 0, "h", "b", "e"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("prNumber");
        assertThatThrownBy(() -> ReviewTask.create(" ", 1, "h", "b", "e"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("repo");
    }
}
