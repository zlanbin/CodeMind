package com.codemind.rule.interfaces.web;

import com.codemind.common.api.Result;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 参数非法（含畸形 JSON）→ 400，统一 {@link Result} 格式（同 memory MemoryExceptionHandler 做法）。
 * 按 assignableTypes 限定，不溢出到其它控制器。
 */
@RestControllerAdvice(assignableTypes = RuleController.class)
public class RuleExceptionHandler {

    public static final int CODE_BAD_REQUEST = 40000;

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Result<Void>> onIllegalArgument(IllegalArgumentException e) {
        return badRequest(e.getMessage());
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Result<Void>> onUnreadable(HttpMessageNotReadableException e) {
        return badRequest("请求体无法解析");
    }

    private static ResponseEntity<Result<Void>> badRequest(String message) {
        return ResponseEntity.badRequest().body(Result.fail(CODE_BAD_REQUEST, message));
    }
}
