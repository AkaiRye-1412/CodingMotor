package com.motorsizer.app.dto;

import java.util.List;
import java.util.Map;

/** A1 响应：一次下发三套模板。 */
public record TemplateResponse(List<String> types, Map<String, TypeTemplateDto> templates) {
}
