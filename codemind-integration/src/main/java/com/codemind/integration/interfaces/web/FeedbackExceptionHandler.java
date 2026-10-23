package com.codemind.integration.interfaces.web;

import com.codemind.common.api.Result;
import com.codemind.integration.application.port.ScmException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** 手动轮询端点的错误映射：参数非法 400，GitHub 读失败 502。按 assignableTypes 限定，不溢出。 */
@RestControllerAdvice(assignableTypes = FeedbackController.class)
public class FeedbackExceptionHandler {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(FeedbackExceptionHandler.class);

    public static final int CODE_BAD_REQUEST = 40000;
    public static final int CODE_UPSTREAM_FAILED = 50200;

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Result<Void>> onIllegalArgument(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Result.fail(CODE_BAD_REQUEST, e.getMessage()));
    }

    @ExceptionHandler(ScmException.class)
    public ResponseEntity<Result<Void>> onScmFailure(ScmException e) {
        log.warn("手动轮询读 GitHub 失败 status={} 原因={}", e.statusCode(), e.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(Result.fail(CODE_UPSTREAM_FAILED, e.getMessage()));
    }
}
