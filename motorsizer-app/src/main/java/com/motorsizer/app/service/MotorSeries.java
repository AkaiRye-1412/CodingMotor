package com.motorsizer.app.service;

import java.util.List;

import com.motorsizer.domain.model.AxisKind;

/**
 * 「系列」筛选（第 67 轮 + 第 68 轮）。
 *
 * <p>直线与旋转电机都没有统一、可靠的「结构」口径，但型号名本身带有系列前缀
 * （旋转：{@code ADR175-A102-S}、{@code ACD85-...}、{@code AXD120-71-S}；直线：
 * {@code ACM...}、{@code AKM...} 等）。故两个页签都把「结构」筛选替换为「系列」，
 * 判定依据是型号名是否以该前缀开头；「其他」采用排除法——型号名不以该轴任一已知
 * 前缀开头即归入。
 */
public final class MotorSeries {

    /** 旋转（力矩）页「系列」选项，按展示顺序。 */
    public static final List<String> ROTARY_SERIES =
            List.of("ADR", "ACD", "AXD", "ACW", "ACR", "AMR", "AXM", "其他");

    /** 直线页「系列」选项，按展示顺序（第 68 轮）。 */
    public static final List<String> LINEAR_SERIES =
            List.of("ACM", "AUM", "ALM", "AJM", "AQM", "AKM", "AKH", "AWM", "AVM", "AVA", "其他");

    private MotorSeries() {
    }

    /** 该轴对应的「系列」选项列表。 */
    public static List<String> forAxis(AxisKind axis) {
        return axis == AxisKind.LINEAR ? LINEAR_SERIES : ROTARY_SERIES;
    }

    /** 型号名是否命中某个系列筛选值（含「其他」）。前缀匹配不区分大小写。 */
    public static boolean matches(String modelName, String series, List<String> knownSeries) {
        if (modelName == null || series == null || series.isBlank()) {
            return false;
        }
        if ("其他".equals(series)) {
            return !isKnownSeries(modelName, knownSeries);
        }
        return modelName.regionMatches(true, 0, series, 0, series.length());
    }

    /** 型号名是否以该轴任一已知系列前缀开头（「其他」取反的依据）。 */
    private static boolean isKnownSeries(String modelName, List<String> knownSeries) {
        for (String prefix : knownSeries) {
            if ("其他".equals(prefix)) {
                continue;
            }
            if (modelName.regionMatches(true, 0, prefix, 0, prefix.length())) {
                return true;
            }
        }
        return false;
    }
}
