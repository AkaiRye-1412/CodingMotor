package com.motorsizer.app.service;

import java.util.List;
import java.util.Locale;

/**
 * 「结构」筛选（冷却方式）的判定口径（第 69 轮）。
 *
 * <p>用户 2026-11-19 要求：直线/旋转两个页签都加「结构」筛选（自然冷却 / 气冷 / 水冷），
 * 判据是型号名里 {@code -A} 为气冷、{@code -W} 为水冷，都不含则自然冷却。真库 435 台实测发现
 * {@code RDM020-A-B2} 这类型号中间的 {@code -A-} 是「A 型」结构代号而非气冷，故定下三级顺序：
 *
 * <ol>
 *   <li><b>型号名结尾</b>是 {@code -A} / {@code -W} → 直接定（最强信号，AUM3/4/5 的冷却变体都长这样）；</li>
 *   <li>否则<b>库里冷却字段</b>有值就用它（入库/录入时已按型号写过；RDM 靠这条不被误判，
 *       也能挡住字段与型号矛盾时把结构代号认成冷却）；</li>
 *   <li>字段为空时才用型号名中间的 {@code -A-} / {@code -W-} 兜底（{@code AKM100-W-B2-S} 这种）。</li>
 * </ol>
 *
 * <p>对外筛选值用「自然冷却」，而库里字段写的是「普通」——两者的换算只在本类做，
 * 前端与校验器都以 {@link #OPTIONS} 为单一来源。
 */
public final class MotorCooling {

    /** 自然冷却（库里字段为「普通」）。 */
    public static final String NATURAL = "自然冷却";

    /** 气冷（型号结尾 {@code -A}）。 */
    public static final String AIR = "气冷";

    /** 水冷（型号结尾 {@code -W}）。 */
    public static final String WATER = "水冷";

    /** 筛选选项，按展示顺序。 */
    public static final List<String> OPTIONS = List.of(NATURAL, AIR, WATER);

    /** 库里「普通」等价于自然冷却，历史数据可能出现的其它写法一并认。 */
    private static final List<String> NATURAL_ALIASES = List.of("普通", "自冷", "自然", "自然冷却");

    private MotorCooling() {
    }

    /**
     * 单台电机的冷却方式。
     *
     * @param modelName    型号名（如 {@code AUM4-S1-S-A}）
     * @param coolingField 库里的冷却字段（普通 / 气冷 / 水冷；可为空）
     * @return {@link #NATURAL} / {@link #AIR} / {@link #WATER} 之一
     */
    public static String of(String modelName, String coolingField) {
        String bySuffix = byModelSuffix(modelName);
        if (bySuffix != null) {
            return bySuffix;
        }
        String field = coolingField == null ? "" : coolingField.trim();
        if (WATER.equals(field)) {
            return WATER;
        }
        if (AIR.equals(field)) {
            return AIR;
        }
        if (NATURAL_ALIASES.contains(field)) {
            return NATURAL;
        }
        // 字段为空（或是不认识的值）：退回型号名中间段兜底。
        return byModelSegment(modelName);
    }

    /**
     * 只看型号名<b>结尾</b>的 {@code -A} / {@code -W}（不看中间段、不看字段）。
     *
     * <p>给样册入库用：那时库里还没有字段可查，而中间段 {@code -A-} 可能是结构代号
     * （{@code AKM200-A-B4}、{@code RDM020-A-B2} 都是普通冷却），不能当冷却证据。
     * 结尾后缀才是冷却变体的稳定写法（{@code AUM4-S1-S-A} / {@code AKM100-W-B2-S}）。
     *
     * @return {@link #AIR} / {@link #WATER}；结尾没有冷却后缀时返回 {@code null}
     */
    public static String bySuffix(String modelName) {
        return byModelSuffix(modelName);
    }

    /** 型号名结尾是 {@code -A} / {@code -W} 时的结论；其它情况返回 {@code null}。 */
    private static String byModelSuffix(String modelName) {
        String name = normalize(modelName);
        if (name.isEmpty()) {
            return null;
        }
        if (name.endsWith("-A")) {
            return AIR;
        }
        if (name.endsWith("-W")) {
            return WATER;
        }
        return null;
    }

    /** 型号名含独立段 {@code -A-} / {@code -W-} 时的结论；都没有则是自然冷却。 */
    private static String byModelSegment(String modelName) {
        String name = normalize(modelName);
        // 水冷优先：`AKM100-W-B2-S` 这类只有水冷段
        if (name.contains("-W-")) {
            return WATER;
        }
        if (name.contains("-A-")) {
            return AIR;
        }
        return NATURAL;
    }

    private static String normalize(String modelName) {
        return modelName == null ? "" : modelName.trim().toUpperCase(Locale.ROOT);
    }
}
