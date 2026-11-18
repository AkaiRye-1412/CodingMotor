package com.motorsizer.app.common;

import java.util.List;

/**
 * 业务异常：携带可识别的错误码与可选字段级原因（Java 规范 11），由全局异常处理器统一转换。
 */
public class BusinessException extends RuntimeException {

    private final ErrorCode errorCode;
    private final List<FieldError> errors;

    public BusinessException(ErrorCode errorCode) {
        this(errorCode, errorCode.defaultMessage(), List.of());
    }

    public BusinessException(ErrorCode errorCode, String message) {
        this(errorCode, message, List.of());
    }

    public BusinessException(ErrorCode errorCode, String message, List<FieldError> errors) {
        super(message);
        this.errorCode = errorCode;
        this.errors = errors == null ? List.of() : List.copyOf(errors);
    }

    public ErrorCode errorCode() {
        return errorCode;
    }

    public List<FieldError> errors() {
        return errors;
    }
}
