package com.motorsizer.app.service;

import com.motorsizer.app.dto.FieldDto;
import com.motorsizer.app.dto.GroupDto;
import com.motorsizer.app.dto.TemplateResponse;
import com.motorsizer.app.dto.TypeTemplateDto;
import com.motorsizer.domain.catalog.MotorCatalogTemplates;
import com.motorsizer.domain.catalog.MotorField;
import com.motorsizer.domain.catalog.MotorTypeTemplate;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A1：把 domain 的 K2 模板转换为接口结构。模板来自代码（T-4），不查库、不下发派生公式。
 */
@Service
public class MotorTemplateService {

    public TemplateResponse getTemplates() {
        Map<String, TypeTemplateDto> templates = new LinkedHashMap<>();
        List<String> types = MotorCatalogTemplates.typeOrder().stream().map(type -> {
            MotorTypeTemplate template = MotorCatalogTemplates.of(type);
            templates.put(type.code(), toDto(template));
            return type.code();
        }).toList();
        return new TemplateResponse(types, templates);
    }

    private TypeTemplateDto toDto(MotorTypeTemplate template) {
        // 下发时把「录入必填」（线圈质量 / 转动惯量）也算进 required：表单要能标红星，
        // 否则用户点保存才知道缺项。它们不进域模型 required，是为了不牵连选型准入。
        Set<String> entryRequired = MotorCatalogTemplates.entryRequiredKeys(template.type());
        List<GroupDto> groups = template.groups().stream()
                .map(group -> new GroupDto(
                        group.title(),
                        group.optionalAdd(),
                        group.fields().stream()
                                .map(field -> toFieldDto(field, entryRequired.contains(field.key())))
                                .toList()))
                .toList();
        return new TypeTemplateDto(
                template.label(),
                template.configs(),
                template.structures(),
                template.coolings(),
                template.bores(),
                groups);
    }

    private FieldDto toFieldDto(MotorField field, boolean entryRequired) {
        return new FieldDto(
                field.key(),
                field.label(),
                field.unit(),
                field.required() || entryRequired,
                field.factor(),
                field.display(),
                field.aliases(),
                field.symbol());
    }
}
