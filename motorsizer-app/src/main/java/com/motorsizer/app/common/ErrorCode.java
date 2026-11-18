package com.motorsizer.app.common;

import org.springframework.http.HttpStatus;

/**
 * 错误码（04 §2.3）：前缀分段字符串码。计算类 {@code CALC_*} 不走 HTTP 4xx，暂未纳入本阶段。
 */
public enum ErrorCode {

    COMMON_VALIDATION_FAILED(HttpStatus.BAD_REQUEST, "参数校验失败"),
    COMMON_INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "服务内部错误"),

    MOTOR_NOT_FOUND(HttpStatus.NOT_FOUND, "电机记录不存在"),
    MOTOR_DUPLICATE(HttpStatus.CONFLICT, "型号名与配置名已存在"),
    MOTOR_SPEC_REQUIRED(HttpStatus.BAD_REQUEST, "必填规格字段缺失"),
    MOTOR_TYPE_MISMATCH(HttpStatus.BAD_REQUEST, "规格结构与电机类型不匹配"),

    REPORT_NO_PLAN(HttpStatus.BAD_REQUEST, "提交的方案数为 0"),
    REPORT_ALL_UNCOMPUTABLE(HttpStatus.BAD_REQUEST, "全部方案都无法计算"),

    IMPORT_JOB_NOT_FOUND(HttpStatus.NOT_FOUND, "导入任务不存在或已清理"),
    IMPORT_PAGE_NOT_FOUND(HttpStatus.NOT_FOUND, "页面不存在"),
    IMPORT_DRAFT_NOT_FOUND(HttpStatus.NOT_FOUND, "草稿不存在"),
    IMPORT_FILE_REJECTED(HttpStatus.BAD_REQUEST, "文件类型/大小/数量不合法"),
    IMPORT_OCR_REQUIRED(HttpStatus.BAD_REQUEST, "该页无文本层，需要 OCR 组件（后续版本）"),
    IMPORT_NOTHING_TO_COMMIT(HttpStatus.BAD_REQUEST, "没有勾选任何草稿");

    private final HttpStatus httpStatus;
    private final String defaultMessage;

    ErrorCode(HttpStatus httpStatus, String defaultMessage) {
        this.httpStatus = httpStatus;
        this.defaultMessage = defaultMessage;
    }

    public HttpStatus httpStatus() {
        return httpStatus;
    }

    public String defaultMessage() {
        return defaultMessage;
    }
}
