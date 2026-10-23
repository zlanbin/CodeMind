package com.codemind.review.domain.report;

/** 意见转入总结的原因，按报告规则的判定阶段区分。 */
public enum Reason {
    NO_LINE,
    OUTSIDE_DIFF,
    BELOW_THRESHOLD,
    OVER_LIMIT
}
