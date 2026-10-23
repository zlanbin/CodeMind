package com.codemind.review.domain.memory;

/**
 * 召回否决记忆失败（M9b）。domain 层异常，把 Feign / HTTP 的具体异常类型挡在 infrastructure，
 * 原异常保留为 {@code cause} 供排查。
 */
public class MemoryRecallException extends RuntimeException {

    public MemoryRecallException(String message, Throwable cause) {
        super(message, cause);
    }
}
