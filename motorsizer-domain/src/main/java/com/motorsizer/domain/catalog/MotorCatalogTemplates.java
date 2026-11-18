package com.motorsizer.domain.catalog;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * K2：三套字段模板（直线 / 力矩 / 音圈）。
 *
 * <p>数据来源：{@code prototype/js/data.js:299-406} 的字段顺序与样册表格一致；
 * 字段 key 对齐 {@code doc/design/04.API接口设计.md} 附录 A（API ↔ DB 一一对应）。
 *
 * <p>关键约束：
 * <ol>
 *   <li>{@code required} 标记同时驱动保存校验、准入判定与星标，必须唯一来源（C-B）；</li>
 *   <li>字段 key 必须与数据库列（驼峰化）完全一致，否则 C2/C3 无法映射；</li>
 *   <li>单位以「录入单位 = 内部单位」为口径，仅音圈质量用 {@code factor} 换算。</li>
 * </ol>
 */
public final class MotorCatalogTemplates {

    private MotorCatalogTemplates() {
    }

    private static final BigDecimal ONE = BigDecimal.ONE;

    private static final List<String> FORM_ONLY = List.of(MotorField.DISPLAY_FORM);
    private static final List<String> DETAIL_AND_FORM =
            List.of(MotorField.DISPLAY_DETAIL, MotorField.DISPLAY_FORM);

    private static final String GROUP_PERFORMANCE = "性能参数";
    private static final String GROUP_MECHANICAL = "机械参数";
    private static final String GROUP_ADOPTION = "准入补充（样册通常不提供，留空即无法判定）";

    /** 表单 + 参数表都显示（选填）。 */
    private static MotorField both(String key, String label, String unit) {
        return new MotorField(key, label, unit, false, ONE, DETAIL_AND_FORM);
    }

    /** 表单 + 参数表都显示（必填）。 */
    private static MotorField bothRequired(String key, String label, String unit) {
        return new MotorField(key, label, unit, true, ONE, DETAIL_AND_FORM);
    }

    /** 仅录入表单显示（选填）。 */
    private static MotorField formOnly(String key, String label, String unit) {
        return new MotorField(key, label, unit, false, ONE, FORM_ONLY);
    }

    /** 仅录入表单显示（必填）。 */
    private static MotorField formOnlyRequired(String key, String label, String unit) {
        return new MotorField(key, label, unit, true, ONE, FORM_ONLY);
    }

    /** 仅录入表单显示，并按 factor 把录入单位换算为内部单位。 */
    private static MotorField formOnly(String key, String label, String unit, BigDecimal factor) {
        return new MotorField(key, label, unit, false, factor, FORM_ONLY);
    }

    /** 表单 + 参数表都显示，并按 factor 把录入单位换算为内部单位。 */
    private static MotorField both(String key, String label, String unit, BigDecimal factor) {
        return new MotorField(key, label, unit, false, factor, DETAIL_AND_FORM);
    }

    private static final MotorTypeTemplate LINEAR = new MotorTypeTemplate(
            MotorType.LINEAR,
            "直线",
            List.of("默认", "串联", "并联"),
            List.of("无铁芯", "有铁芯"),
            List.of("普通", "水冷", "气冷"),
            List.of(),
            List.of(
                    new MotorFieldGroup(GROUP_PERFORMANCE, false, List.of(
                            bothRequired("forceCont", "持续推力", "N"),
                            bothRequired("forcePeak", "峰值推力", "N"),
                            bothRequired("kf", "力常数 ±10%", "N/Arms"),
                            bothRequired("ke", "反电势常数 ±10%", "Vpeak/(m/s)"),
                            both("km", "电机常数 @25°C", "N/SqRt(W)"),
                            bothRequired("r25", "相间电阻 @25°C ±10%", "Ω"),
                            both("l", "相间电感 ±30%", "mH"),
                            formOnly("te", "电气时间常数", "ms"),
                            bothRequired("iCont", "持续电流 ({cool}) @100°C", "Arms"),
                            bothRequired("iPeak", "峰值电流", "Arms"),
                            formOnly("pn", "持续热功率 ({cool}) @100°C", "W"),
                            formOnlyRequired("tMax", "最高线圈温度", "°C"),
                            formOnlyRequired("kth", "热耗散常数 ({cool})", "W/°C"),
                            formOnlyRequired("uBus", "最高母线电压", "Vdc"),
                            formOnly("polePitch", "电磁周期", "mm"),
                            formOnly("magForce", "磁吸力", "N")
                    )),
                    new MotorFieldGroup(GROUP_MECHANICAL, false, List.of(
                            both("moverMass", "线圈质量 ({cool})", "kg"),
                            both("coilLength", "线圈长度 ({cool})", "mm"),
                            formOnly("trackMass", "磁轨质量 (每米)", "kg/m")
                    )),
                    new MotorFieldGroup(GROUP_ADOPTION, true, List.of(
                            formOnly("maxSpeedPeak", "最高速度 @峰值推力", "m/s"),
                            formOnly("maxSpeedCont", "最高速度 @持续推力", "m/s")
                    ))
            )
    );

    private static final MotorTypeTemplate TORQUE = new MotorTypeTemplate(
            MotorType.TORQUE,
            "力矩",
            List.of("串联", "并联", "默认"),
            List.of("有铁芯", "无铁芯", "超声波力矩电机"),
            List.of("普通", "气冷", "水冷"),
            List.of("标准", "特殊"),
            List.of(
                    new MotorFieldGroup(GROUP_PERFORMANCE, false, List.of(
                            bothRequired("torqueCont", "持续转矩 @100°C", "Nm"),
                            bothRequired("torquePeak", "峰值转矩", "Nm"),
                            bothRequired("kt", "转矩常数 ±10%", "Nm/Arms"),
                            bothRequired("ke", "反电势常数 ±10%", "Vpeak/rpm"),
                            both("km", "电机常数 @25°C", "Nm/SqRt(W)"),
                            bothRequired("r25", "相间电阻 @25°C ±10%", "Ω"),
                            both("l", "相间电感 ±20%", "mH"),
                            formOnly("te", "电气时间常数", "ms"),
                            bothRequired("iCont", "持续电流 @100°C", "Arms"),
                            bothRequired("iPeak", "峰值电流", "Arms"),
                            formOnly("pn", "持续热功率 @100°C", "W"),
                            formOnlyRequired("tMax", "最高线圈温度", "°C"),
                            formOnlyRequired("kth", "热耗散常数", "W/°C"),
                            formOnlyRequired("uBus", "最高母线电压", "Vdc"),
                            formOnly("poles", "极数", "-"),
                            // 力矩样册均提供双转速阈值，故为必填（与直线/音圈不同）
                            formOnlyRequired("maxSpeedPeak", "最高转速 @峰值转矩", "rpm"),
                            formOnlyRequired("maxSpeedCont", "最高转速 @持续转矩", "rpm")
                    )),
                    new MotorFieldGroup(GROUP_MECHANICAL, false, List.of(
                            formOnly("totalMass", "总质量", "kg"),
                            both("inertia", "转动惯量", "kg·m²"),
                            formOnly("runoutAxial", "轴向端跳", "μm"),
                            formOnly("runoutRadial", "径向端跳", "μm"),
                            formOnly("loadAxialNormal", "最大轴向载荷 (正常安装)", "N"),
                            formOnly("loadAxialInverted", "最大轴向载荷 (倒装/侧装)", "N"),
                            formOnly("loadTorqueNormal", "最大扭矩载荷 (正常安装)", "Nm"),
                            formOnly("loadTorqueInverted", "最大扭矩载荷 (倒装/侧装)", "Nm")
                    ))
            )
    );

    private static final MotorTypeTemplate COIL = new MotorTypeTemplate(
            MotorType.COIL,
            "音圈",
            List.of("默认"),
            List.of("音圈"),
            List.of("普通"),
            List.of(),
            List.of(
                    new MotorFieldGroup(GROUP_PERFORMANCE, false, List.of(
                            both("stroke", "行程", "mm"),
                            bothRequired("forceCont", "持续推力 @100°C", "N"),
                            bothRequired("forcePeak", "峰值推力", "N"),
                            bothRequired("kf", "力常数 ±10%", "N/A"),
                            bothRequired("ke", "反电势常数 ±10%", "V/(m/s)"),
                            both("km", "电机常数 @25°C", "N/SqRt(W)"),
                            bothRequired("r25", "电阻 @25°C ±10%", "Ω"),
                            both("l", "电感 ±20%", "mH"),
                            formOnly("te", "电气时间常数", "ms"),
                            bothRequired("iCont", "持续电流 @100°C", "A"),
                            bothRequired("iPeak", "峰值电流", "A"),
                            formOnly("pn", "持续热功率 @100°C", "W"),
                            formOnlyRequired("tMax", "最高线圈温度", "°C"),
                            formOnlyRequired("kth", "热耗散常数", "W/°C"),
                            formOnlyRequired("uBus", "最高电压", "Vdc")
                    )),
                    new MotorFieldGroup(GROUP_MECHANICAL, false, List.of(
                            // 音圈机械参数按 g 录入，内部统一按 kg 计算（第 63 轮 Q4）
                            both("moverMass", "线圈质量", "g", new BigDecimal("0.001")),
                            both("coreMass", "铁芯质量", "g", new BigDecimal("0.001")),
                            both("gap", "运动间隙", "mm")
                    )),
                    new MotorFieldGroup(GROUP_ADOPTION, true, List.of(
                            formOnly("maxSpeedPeak", "最高速度 @峰值推力", "m/s"),
                            formOnly("maxSpeedCont", "最高速度 @持续推力", "m/s")
                    ))
            )
    );

    private static final Map<MotorType, MotorTypeTemplate> TEMPLATES = Map.of(
            MotorType.LINEAR, LINEAR,
            MotorType.TORQUE, TORQUE,
            MotorType.COIL, COIL
    );

    /** A1 下发顺序：直线 → 力矩 → 音圈。 */
    private static final List<MotorType> TYPE_ORDER = List.of(MotorType.LINEAR, MotorType.TORQUE, MotorType.COIL);

    public static List<MotorType> typeOrder() {
        return TYPE_ORDER;
    }

    public static MotorTypeTemplate of(MotorType type) {
        MotorTypeTemplate template = TEMPLATES.get(type);
        if (template == null) {
            throw new IllegalArgumentException("未知电机类型: " + type);
        }
        return template;
    }

    public static Map<MotorType, MotorTypeTemplate> all() {
        return TEMPLATES;
    }
}
