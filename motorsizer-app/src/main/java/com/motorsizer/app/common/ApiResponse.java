package com.motorsizer.app.common;

/**
 * 统一响应信封 {code, message, data}（API-3）。
 */
public record ApiResponse<T>(String code, String message, T data) {

    public static final String OK = "OK";

    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(OK, "成功", data);
    }

    public static <T> ApiResponse<T> ok(String message, T data) {
        return new ApiResponse<>(OK, message, data);
    }

    public static <T> ApiResponse<T> error(String code, String message, T data) {
        return new ApiResponse<>(code, message, data);
    }
}
