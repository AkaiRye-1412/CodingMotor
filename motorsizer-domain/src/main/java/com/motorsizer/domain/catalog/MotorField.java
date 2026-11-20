package com.motorsizer.domain.catalog;

import java.math.BigDecimal;
import java.util.List;

/**
 * 一个可录入的电参数定义。
 *
 * @param key      接口字段名 / 数据库列（驼峰）
 * @param label    录入表单与参数表共用的标签；可含 {@code {cool}} 冷却方式占位符
 * @param unit     单位，**同时就是内部单位**（三套模板全为 1:1，音圈质量也是 kg，CAL-25）
 * @param required 是否必填。该标记是「入库门槛 = 准入门槛 = 星标」的唯一来源（C-B）
 * @param factor   录入单位 → 内部单位的换算系数；CAL-25 起恒为 1（样册 g→kg 改由入库层按行单位换算）
 * @param display  出现位置：{@code detail}（M8 参数表）/{@code form}（M10 录入表单）
 * @param aliases  样册识别用的别名（Q2 裁定，随 A1 下发）；可为空。样册常见变体如 R25 ↔ 相间电阻
 * @param symbol   样册参数表里的物理符号（如 Tcn/Kt/R25），是识别的一等匹配键（优先级高于别名）。
 *                 样册「符号」列稳定唯一，比中文标签（带角标/括号）更好匹配。可为空。
 */
public record MotorField(
        String key,
        String label,
        String unit,
        boolean required,
        BigDecimal factor,
        List<String> display,
        List<String> aliases,
        String symbol) {

    public static final String DISPLAY_DETAIL = "detail";
    public static final String DISPLAY_FORM = "form";

    public MotorField {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("字段 key 不能为空");
        }
        display = List.copyOf(display);
        aliases = aliases == null ? List.of() : List.copyOf(aliases);
    }

    public boolean showsInDetail() {
        return display.contains(DISPLAY_DETAIL);
    }

    public boolean showsInForm() {
        return display.contains(DISPLAY_FORM);
    }

    /** 返回带别名的副本（用于 K2 模板构造期链式标注，06 §6 Q2）。 */
    public MotorField withAliases(String... alias) {
        return new MotorField(key, label, unit, required, factor, display, List.of(alias), symbol);
    }

    /** 返回带符号的副本（用于 K2 模板构造期链式标注符号）。 */
    public MotorField withSymbol(String symbolValue) {
        return new MotorField(key, label, unit, required, factor, display, aliases, symbolValue);
    }
}
