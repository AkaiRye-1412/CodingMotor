package com.motorsizer.app.dto;

import java.math.BigDecimal;
import java.util.List;

/** A1 下发的一个字段定义（不含派生公式）。 */
public record FieldDto(
        String key,
        String label,
        String unit,
        boolean required,
        BigDecimal factor,
        List<String> display,
        List<String> aliases) {
}
