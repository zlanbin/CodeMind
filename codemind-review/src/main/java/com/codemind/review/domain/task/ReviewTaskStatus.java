package com.codemind.review.domain.task;

import java.util.Set;

/**
 * 评审任务状态机。
 *
 * <p>{@code CREATED → PLANNING → REVIEWING → AGGREGATING → PUBLISHED}，任一阶段可转
 * {@code FAILED}；{@code FAILED} 可从 checkpoint 恢复回除 PUBLISHED 外的任一进行中阶段（M3 用）。
 */
public enum ReviewTaskStatus {

    CREATED,
    PLANNING,
    REVIEWING,
    AGGREGATING,
    PUBLISHED,
    FAILED;

    /** 该状态可以转去哪些状态。 */
    public Set<ReviewTaskStatus> allowedNext() {
        return switch (this) {
            case CREATED -> Set.of(PLANNING, FAILED);
            case PLANNING -> Set.of(REVIEWING, FAILED);
            case REVIEWING -> Set.of(AGGREGATING, FAILED);
            case AGGREGATING -> Set.of(PUBLISHED, FAILED);
            case PUBLISHED -> Set.of();
            // 失败后从 checkpoint 续跑，回到原来的进行中阶段
            case FAILED -> Set.of(PLANNING, REVIEWING, AGGREGATING);
        };
    }

    public boolean canTransitionTo(ReviewTaskStatus target) {
        return allowedNext().contains(target);
    }

    public boolean isTerminal() {
        return this == PUBLISHED;
    }
}
