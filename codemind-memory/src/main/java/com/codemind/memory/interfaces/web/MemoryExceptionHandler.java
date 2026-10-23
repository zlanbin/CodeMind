package com.codemind.memory.interfaces.web;

import com.codemind.common.api.Result;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * 参数非法（含未知枚举、畸形 JSON、缺失或类型不对的查询参数）→ 400，统一 {@link Result} 格式。
 * 按 assignableTypes 限定，不溢出到其它控制器。库里脏数据由仓储转成 IllegalStateException，走默认 500。
 */
@RestControllerAdvice(assignableTypes = {MemoryController.class, TaskFingerprintController.class})
public class MemoryExceptionHandler {

    public static final int CODE_BAD_REQUEST = 40000;

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Result<Void>> onIllegalArgument(IllegalArgumentException e) {
        return badRequest(e.getMessage());
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Result<Void>> onUnreadable(HttpMessageNotReadableException e) {
        return badRequest("请求体无法解析");
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<Result<Void>> onMissingParameter(MissingServletRequestParameterException e) {
        return badRequest("缺少参数：" + e.getParameterName());
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Result<Void>> onTypeMismatch(MethodArgumentTypeMismatchException e) {
        return badRequest("参数类型不对：" + e.getName());
    }

    private static ResponseEntity<Result<Void>> badRequest(String message) {
        return ResponseEntity.badRequest().body(Result.fail(CODE_BAD_REQUEST, message));
    }
}
