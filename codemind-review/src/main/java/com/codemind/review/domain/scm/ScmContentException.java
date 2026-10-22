package com.codemind.review.domain.scm;

/**
 * 取 PR 内容失败（M7a）。domain 层异常，把 Feign / HTTP 的具体异常类型挡在 infrastructure，
 * 原异常保留为 {@code cause} 供排查。
 */
public class ScmContentException extends RuntimeException {

    public ScmContentException(String message, Throwable cause) {
        super(message, cause);
    }
}
