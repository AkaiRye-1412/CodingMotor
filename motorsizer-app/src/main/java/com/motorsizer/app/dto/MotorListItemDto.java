package com.motorsizer.app.dto;

import java.time.LocalDate;
import java.time.LocalDateTime;

/** C1 列表项：不含规格参数（避免 N+1 与响应膨胀）。 */
public record MotorListItemDto(
        Long motorId,
        String modelName,
        String configName,
        String motorType,
        String structure,
        String cooling,
        String bore,
        String manufacturer,
        String series,
        String entryMethod,
        LocalDate enteredOn,
        LocalDateTime updatedAt) {
}
