package com.motorsizer.domain.catalog;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 一种电机类型的完整字段模板（K2）。
 *
 * <p>字段定义只存在于代码中（T-4：代码内定义 + API 下发），不建字典表。
 */
public record MotorTypeTemplate(
        MotorType type,
        String label,
        List<String> configs,
        List<String> structures,
        List<String> coolings,
        List<String> bores,
        List<MotorFieldGroup> groups) {

    public MotorTypeTemplate {
        configs = List.copyOf(configs);
        structures = List.copyOf(structures);
        coolings = List.copyOf(coolings);
        bores = List.copyOf(bores);
        groups = List.copyOf(groups);
    }

    /** 该类型的全部字段（跨分组，保序）。 */
    public List<MotorField> fields() {
        List<MotorField> all = new ArrayList<>();
        groups.forEach(group -> all.addAll(group.fields()));
        return all;
    }

    /** 字段白名单：只允许这些 key 出现在 spec 中（C3 规则 2）。 */
    public Set<String> allowedKeys() {
        Set<String> keys = new LinkedHashSet<>();
        fields().forEach(field -> keys.add(field.key()));
        return keys;
    }

    /** 必填集合（C3 规则 1），由 {@code req} 标记派生，不硬编码。 */
    public List<String> requiredKeys() {
        return fields().stream().filter(MotorField::required).map(MotorField::key).toList();
    }

    public Optional<MotorField> field(String key) {
        return fields().stream().filter(field -> field.key().equals(key)).findFirst();
    }

    public boolean allowsStructure(String value) {
        return structures.contains(value);
    }

    public boolean allowsCooling(String value) {
        return coolings.contains(value);
    }

    public boolean allowsBore(String value) {
        return bores.contains(value);
    }
}
