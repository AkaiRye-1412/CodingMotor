package com.motorsizer.app.dto;

import java.util.List;

/** A1 下发的一种电机类型模板。 */
public record TypeTemplateDto(
        String label,
        List<String> configs,
        List<String> structures,
        List<String> coolings,
        List<String> bores,
        List<GroupDto> groups) {
}
