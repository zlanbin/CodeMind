package com.codemind.integration.interfaces.web;

import com.codemind.common.api.Result;
import com.codemind.integration.application.InvalidSignatureException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Webhook 入口的错误映射：验签失败 401，请求体不合法 400。
 */
@RestControllerAdvice(assignableTypes = GithubWebhookController.class)
public class WebhookExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(WebhookExceptionHandler.class);

    public static final int CODE_UNAUTHORIZED = 40100;
    public static final int CODE_BAD_REQUEST = 40000;

    @ExceptionHandler(InvalidSignatureException.class)
    public ResponseEntity<Result<Void>> onInvalidSignature(InvalidSignatureException e) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(Result.fail(CODE_UNAUTHORIZED, e.getMessage()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Result<Void>> onIllegalArgument(IllegalArgumentException e) {
        log.warn("webhook 请求不合法: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Result.fail(CODE_BAD_REQUEST, e.getMessage()));
    }
}
