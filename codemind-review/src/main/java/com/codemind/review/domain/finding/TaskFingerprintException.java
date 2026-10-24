package com.codemind.review.domain.finding;

/**
 * 查询/登记已发布指纹失败（M10）。domain 层异常，把 Feign / HTTP 的具体异常类型挡在 infrastructure，
 * 原异常保留为 {@code cause} 供排查。
 */
public class TaskFingerprintException extends RuntimeException {

    public TaskFingerprintException(String message, Throwable cause) {
        super(message, cause);
    }
}
