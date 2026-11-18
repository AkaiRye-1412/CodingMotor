package com.motorsizer.app.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.List;

/**
 * 全局异常处理（04 §2.4）：各 Controller 不重复 try-catch。
 *
 * <p>禁止向前端返回 SQL 详情、堆栈、内部路径（Java 规范 12 / api-rules 5.3）。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiResponse<ErrorsPayload>> handleBusiness(BusinessException ex) {
        ErrorsPayload payload = ex.errors().isEmpty() ? null : ErrorsPayload.of(ex.errors());
        return ResponseEntity.status(ex.errorCode().httpStatus())
                .body(ApiResponse.error(ex.errorCode().name(), ex.getMessage(), payload));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<ErrorsPayload>> handleValidation(MethodArgumentNotValidException ex) {
        List<FieldError> errors = ex.getBindingResult().getFieldErrors().stream()
                .map(fieldError -> new FieldError(fieldError.getField(), fieldError.getDefaultMessage()))
                .toList();
        return ResponseEntity.badRequest()
                .body(ApiResponse.error(ErrorCode.COMMON_VALIDATION_FAILED.name(),
                        ErrorCode.COMMON_VALIDATION_FAILED.defaultMessage(),
                        ErrorsPayload.of(errors)));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<ErrorsPayload>> handleUnreadable(HttpMessageNotReadableException ex) {
        return ResponseEntity.badRequest()
                .body(ApiResponse.error(ErrorCode.COMMON_VALIDATION_FAILED.name(),
                        "请求体格式错误",
                        ErrorsPayload.of(List.of(new FieldError("body", "请求体无法解析")))));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<ErrorsPayload>> handleUnexpected(Exception ex) {
        log.error("未预期异常", ex);
        return ResponseEntity.status(ErrorCode.COMMON_INTERNAL_ERROR.httpStatus())
                .body(ApiResponse.error(ErrorCode.COMMON_INTERNAL_ERROR.name(),
                        ErrorCode.COMMON_INTERNAL_ERROR.defaultMessage(), null));
    }
}
