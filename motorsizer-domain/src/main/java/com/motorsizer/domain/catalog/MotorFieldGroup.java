package com.motorsizer.domain.catalog;

import java.util.List;

/**
 * 字段分组，对应样册表格中的一段（性能参数 / 机械参数 / 准入补充）。
 *
 * @param title       分组标题
 * @param optionalAdd 是否为「准入补充」类分组（样册通常不提供，留空即无法判定准入②）
 * @param fields      组内字段，顺序即界面顺序
 */
public record MotorFieldGroup(String title, boolean optionalAdd, List<MotorField> fields) {

    public MotorFieldGroup {
        if (fields == null || fields.isEmpty()) {
            throw new IllegalArgumentException("字段分组不能为空: " + title);
        }
        fields = List.copyOf(fields);
    }
}
