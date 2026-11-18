package com.motorsizer.app.dto;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Map;

/**
 * C2 详情：公共属性 + spec。
 * <p>{@code spec} 结构随 motorType 变化，且内含 {@code derived} 派生项（只读、不入库）。
 */
public record MotorDetailDto(
        Long motorId,
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
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        Map<String, Object> spec) {
}
