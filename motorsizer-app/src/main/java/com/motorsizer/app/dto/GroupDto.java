package com.motorsizer.app.dto;

import java.util.List;

/** A1 下发的字段分组。 */
public record GroupDto(String title, boolean optionalAdd, List<FieldDto> fields) {
}
