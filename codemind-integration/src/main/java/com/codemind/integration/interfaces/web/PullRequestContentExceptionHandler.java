package com.codemind.integration.interfaces.web;

import com.codemind.common.api.Result;
import com.codemind.integration.application.port.ScmException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * internal PR 内容端点的错误映射（M7a）：上游 GitHub 调用失败 → 502 Bad Gateway。
 *
 * <p>单独一个 advice 而不是扩展 {@link WebhookExceptionHandler}：后者按
 * {@code assignableTypes = GithubWebhookController.class} 限定，管不到本控制器，
 * 而放宽它的范围会让 webhook 的 401/400 映射溢出到新端点上。
 */
@RestControllerAdvice(assignableTypes = PullRequestContentController.class)
public class PullRequestContentExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(PullRequestContentExceptionHandler.class);

    public static final int CODE_UPSTREAM_FAILED = 50200;

    @ExceptionHandler(ScmException.class)
    public ResponseEntity<Result<Void>> onScmFailure(ScmException e) {
        log.warn("GitHub 调用失败 status={} 原因={}", e.statusCode(), e.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .body(Result.fail(CODE_UPSTREAM_FAILED, e.getMessage()));
    }
}
