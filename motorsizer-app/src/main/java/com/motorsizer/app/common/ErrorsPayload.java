package com.motorsizer.app.common;

import java.util.List;

/**
 * 校验失败时信封的 data 部分。
 */
public record ErrorsPayload(List<FieldError> errors) {

    public static ErrorsPayload of(List<FieldError> errors) {
        return new ErrorsPayload(List.copyOf(errors));
    }
}
