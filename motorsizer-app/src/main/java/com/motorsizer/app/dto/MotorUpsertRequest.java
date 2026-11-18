package com.motorsizer.app.dto;

import java.time.LocalDate;
import java.util.Map;

/**
 * C3 / C4 共用请求体（C4 为整体替换语义）。
 *
 * <p>{@code spec} 用 {@code Map<String, Object>} 承载：字段结构随 motorType 变化，
 * 且需要识别「模板外的字段」与「嵌套对象（如 derived）」后给出精确错误码。
 */
public record MotorUpsertRequest(
        String modelName,
        String configName,
        String motorType,
        String structure,
        String cooling,
        String bore,
        String manufacturer,
        String series,
        String sourceDoc,
        String sourcePage,
        String entryMethod,
        LocalDate enteredOn,
        String note,
        Map<String, Object> spec) {
}
