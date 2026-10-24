package com.codemind.review.domain.rule;

/**
 * 召回规则条款失败（M11）。domain 层异常，把 Feign / HTTP 的具体异常类型挡在 infrastructure，
 * 原异常保留为 {@code cause} 供排查。
 */
public class RuleRecallException extends RuntimeException {

    public RuleRecallException(String message, Throwable cause) {
        super(message, cause);
    }
}
