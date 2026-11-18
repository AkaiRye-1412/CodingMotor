package com.motorsizer.app.common;

/**
 * 参数校验错误定位（04 §1.3）：field 为请求体路径（如 {@code spec.kth}），reason 为人类可读原因。
 */
public record FieldError(String field, String reason) {
}
