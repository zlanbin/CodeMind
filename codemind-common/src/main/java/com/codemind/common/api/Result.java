package com.codemind.common.api;

/**
 * 统一响应体。
 *
 * @param code    0 表示成功，非 0 为错误码
 * @param message 提示信息
 * @param data    业务数据
 */
public record Result<T>(int code, String message, T data) {

    public static final int OK = 0;

    public static <T> Result<T> ok(T data) {
        return new Result<>(OK, "ok", data);
    }

    public static <T> Result<T> fail(int code, String message) {
        return new Result<>(code, message, null);
    }

    public boolean isSuccess() {
        return code == OK;
    }
}
