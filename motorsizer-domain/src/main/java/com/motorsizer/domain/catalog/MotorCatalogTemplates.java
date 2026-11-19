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
 *   <li>单位以「录入单位 = 内部单位」为口径，仅音圈质量用 {@code factor} 换算；</li>
 *   <li>{@code aliases} 为样册识别用别名（06 §6 Q2），随 A1 下发，不影响录入与计算。</li>
 * </ol>
 */
public final class MotorCatalogTemplates {

    private MotorCatalogTemplates() {
    }

    private static final BigDecimal ONE = BigDecimal.ONE;

    private static final List<String> FORM_ONLY = List.of(MotorField.DISPLAY_FORM);
    private static final List<String> DETAIL_AND_FORM =
            List.of(MotorField.DISPLAY_DETAIL, MotorField.DISPLAY_FORM);
    private static final List<String> NO_ALIASES = List.of();

    private static final String GROUP_PERFORMANCE = "性能参数";
    private static final String GROUP_MECHANICAL = "机械参数";
    private static final String GROUP_ADOPTION = "准入补充（样册通常不提供，留空即无法判定）";

    /** 表单 + 参数表都显示（选填）。 */
    private static MotorField both(String key, String label, String unit) {
        return new MotorField(key, label, unit, false, ONE, DETAIL_AND_FORM, NO_ALIASES, null);
    }

    /** 表单 + 参数表都显示（必填）。 */
    private static MotorField bothRequired(String key, String label, String unit) {
        return new MotorField(key, label, unit, true, ONE, DETAIL_AND_FORM, NO_ALIASES, null);
    }

    /** 仅录入表单显示（选填）。 */
    private static MotorField formOnly(String key, String label, String unit) {
        return new MotorField(key, label, unit, false, ONE, FORM_ONLY, NO_ALIASES, null);
    }

    /** 仅录入表单显示（必填）。 */
    private static MotorField formOnlyRequired(String key, String label, String unit) {
        return new MotorField(key, label, unit, true, ONE, FORM_ONLY, NO_ALIASES, null);
    }

    /** 仅录入表单显示，并按 factor 把录入单位换算为内部单位。 */
    private static MotorField formOnly(String key, String label, String unit, BigDecimal factor) {
        return new MotorField(key, label, unit, false, factor, FORM_ONLY, NO_ALIASES, null);
    }

    /** 表单 + 参数表都显示，并按 factor 把录入单位换算为内部单位。 */
    private static MotorField both(String key, String label, String unit, BigDecimal factor) {
        return new MotorField(key, label, unit, false, factor, DETAIL_AND_FORM, NO_ALIASES, null);
    }

    // 直线 / 力矩共用的别名（同 key 在两套模板里语义一致，C-B 不变量）
    // 中文写法 + 英文/缩写，覆盖国内外样册常见排版
    private static final String[] A_FORCE_CONT = {
            "持续推力", "连续推力", "额定推力", "持续力", "连续力",
            "Continuous force", "Cont. force", "F_cont", "Fcont", "Fc", "Rated force"
    };
    private static final String[] A_FORCE_PEAK = {
            "峰值推力", "最大推力", "峰值力",
            "Peak force", "Max. force", "F_peak", "Fpeak", "Fp"
    };
    private static final String[] A_KF = {
            "力常数", "推力常数", "推力系数",
            "Force constant", "Force sensitivity", "Kf", "K_f"
    };
    private static final String[] A_KE = {
            "反电势常数", "反电动势常数", "反电势", "Back EMF",
            "Back EMF constant", "Voltage constant", "Ke", "K_e", "Kbemf"
    };
    private static final String[] A_KM = {"电机常数", "Motor constant", "Km", "K_m"};
    private static final String[] A_R25 = {
            "相间电阻", "线间电阻", "电阻", "相电阻", "R25",
            "Phase resistance", "Resistance", "R_phase", "Rph"
    };
    private static final String[] A_L = {
            "相间电感", "线间电感", "电感",
            "Phase inductance", "Inductance", "L_phase", "Lph"
    };
    private static final String[] A_TE = {"电气时间常数", "Electrical time constant", "Te"};
    private static final String[] A_ICONT = {
            "持续电流", "连续电流", "额定电流", "Icont",
            "Continuous current", "Cont. current", "I_cont", "Icont", "Ic", "Rated current"
    };
    private static final String[] A_IPEAK = {
            "峰值电流", "最大电流",
            "Peak current", "Max. current", "I_peak", "Ipeak", "Ip"
    };
    private static final String[] A_PN = {"持续热功率", "热功率", "Thermal power", "Pn", "P_n"};
    private static final String[] A_TMAX = {
            "最高线圈温度", "最高温度", "最大温度", "Tmax",
            "Max. coil temp", "Max coil temperature", "T_max", "Max. temperature"
    };
    private static final String[] A_KTH = {
            "热耗散常数", "散热常数", "热阻",
            "Thermal resistance", "Thermal dissipation", "Kth", "R_th"
    };
    private static final String[] A_UBUS = {
            "最高母线电压", "母线电压", "供电电压", "Ubus",
            "Bus voltage", "DC bus voltage", "U_bus", "Vdc", "Supply voltage"
    };
    private static final String[] A_POLE_PITCH = {"电磁周期", "极距", "Pole pitch", "Pole-pitch"};
    private static final String[] A_MAG_FORCE = {"磁吸力", "Magnetic attraction", "Attractive force"};
    private static final String[] A_MOVER_MASS = {
            "线圈质量", "动子质量", "Mover mass", "Moving mass", "Forcer mass"
    };
    private static final String[] A_COIL_LENGTH = {"线圈长度", "Coil length", "Forcer length"};
    private static final String[] A_TRACK_MASS = {"磁轨质量", "Track mass", "Magnet track mass"};
    private static final String[] A_MAX_SPEED_PEAK_L = {
            "最高速度 @峰值推力", "峰值最高速度",
            "Max. speed @peak", "Peak max speed", "V_max_peak"
    };
    private static final String[] A_MAX_SPEED_CONT_L = {
            "最高速度 @持续推力", "持续最高速度",
            "Max. speed @cont", "Cont. max speed", "V_max_cont"
    };
    // 力矩独有
    private static final String[] A_TORQUE_CONT = {
            "持续转矩", "连续转矩", "额定转矩",
            "Continuous torque", "Cont. torque", "T_cont", "Tcont", "Rated torque"
    };
    private static final String[] A_TORQUE_PEAK = {
            "峰值转矩", "最大转矩",
            "Peak torque", "Max. torque", "T_peak", "Tpeak", "Tp"
    };
    private static final String[] A_KT = {
            "转矩常数", "力矩常数",
            "Torque constant", "Kt", "K_t"
    };
    private static final String[] A_POLES = {"极数", "Poles", "Pole count", "Number of poles"};
    private static final String[] A_TOTAL_MASS = {"总质量", "Total mass"};
    private static final String[] A_INERTIA = {"转动惯量", "Moment of inertia", "Inertia", "J"};
    private static final String[] A_MAX_SPEED_PEAK_T = {
            "最高转速 @峰值转矩", "最高转速 @峰值扭矩", "峰值最高转速",
            "Max. speed @peak", "Peak max speed", "N_max_peak"
    };
    private static final String[] A_MAX_SPEED_CONT_T = {
            "最高转速 @持续转矩", "最高转速 @持续扭矩", "持续最高转速",
            "Max. speed @cont", "Cont. max speed", "N_max_cont"
    };
    // 音圈独有
    private static final String[] A_STROKE = {"行程", "Stroke", "Travel"};
    private static final String[] A_CORE_MASS = {"铁芯质量", "Core mass"};
    private static final String[] A_GAP = {"运动间隙", "Gap", "Air gap", "Moving gap"};

    private static final MotorTypeTemplate LINEAR = new MotorTypeTemplate(
            MotorType.LINEAR,
            "直线",
            List.of("默认", "串联", "并联"),
            List.of("无铁芯", "有铁芯"),
            List.of("普通", "水冷", "气冷"),
            List.of(),
            List.of(
                    new MotorFieldGroup(GROUP_PERFORMANCE, false, List.of(
                            bothRequired("forceCont", "持续推力", "N").withAliases(A_FORCE_CONT).withSymbol("Fcn"),
                            bothRequired("forcePeak", "峰值推力", "N").withAliases(A_FORCE_PEAK).withSymbol("Fpk"),
                            bothRequired("kf", "力常数 ±10%", "N/Arms").withAliases(A_KF).withSymbol("Kf"),
                            bothRequired("ke", "反电势常数 ±10%", "Vpeak/(m/s)").withAliases(A_KE).withSymbol("Ke"),
                            both("km", "电机常数 @25°C", "N/SqRt(W)").withAliases(A_KM).withSymbol("Km"),
                            bothRequired("r25", "相间电阻 @25°C ±10%", "Ω").withAliases(A_R25).withSymbol("R25"),
                            both("l", "相间电感 ±30%", "mH").withAliases(A_L).withSymbol("L"),
                            formOnly("te", "电气时间常数", "ms").withAliases(A_TE).withSymbol("Te"),
                            bothRequired("iCont", "持续电流 ({cool}) @100°C", "Arms").withAliases(A_ICONT).withSymbol("Icn"),
                            bothRequired("iPeak", "峰值电流", "Arms").withAliases(A_IPEAK).withSymbol("Ipk"),
                            formOnly("pn", "持续热功率 ({cool}) @100°C", "W").withAliases(A_PN).withSymbol("Pcn"),
                            formOnly("tMax", "最高线圈温度", "°C").withAliases(A_TMAX).withSymbol("Tmax"),
                            formOnlyRequired("kth", "热耗散常数 ({cool})", "W/°C").withAliases(A_KTH).withSymbol("Kth"),
                            formOnlyRequired("uBus", "最高母线电压", "Vdc").withAliases(A_UBUS).withSymbol("Ubus"),
                            formOnly("polePitch", "电磁周期", "mm").withAliases(A_POLE_PITCH),
                            formOnly("magForce", "磁吸力", "N").withAliases(A_MAG_FORCE)
                    )),
                    new MotorFieldGroup(GROUP_MECHANICAL, false, List.of(
                            both("moverMass", "线圈质量 ({cool})", "kg").withAliases(A_MOVER_MASS),
                            both("coilLength", "线圈长度 ({cool})", "mm").withAliases(A_COIL_LENGTH),
                            formOnly("trackMass", "磁轨质量 (每米)", "kg/m").withAliases(A_TRACK_MASS)
                    )),
                    new MotorFieldGroup(GROUP_ADOPTION, true, List.of(
                            formOnly("maxSpeedPeak", "最高速度 @峰值推力", "m/s").withAliases(A_MAX_SPEED_PEAK_L),
                            formOnly("maxSpeedCont", "最高速度 @持续推力", "m/s").withAliases(A_MAX_SPEED_CONT_L)
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
                            bothRequired("torqueCont", "持续转矩 @100°C", "Nm").withAliases(A_TORQUE_CONT).withSymbol("Tcn"),
                            bothRequired("torquePeak", "峰值转矩", "Nm").withAliases(A_TORQUE_PEAK).withSymbol("Tpk"),
                            bothRequired("kt", "转矩常数 ±10%", "Nm/Arms").withAliases(A_KT).withSymbol("Kt"),
                            bothRequired("ke", "反电势常数 ±10%", "Vpeak/rpm").withAliases(A_KE).withSymbol("Ke"),
                            both("km", "电机常数 @25°C", "Nm/SqRt(W)").withAliases(A_KM).withSymbol("Km"),
                            bothRequired("r25", "相间电阻 @25°C ±10%", "Ω").withAliases(A_R25).withSymbol("R25"),
                            both("l", "相间电感 ±20%", "mH").withAliases(A_L).withSymbol("L"),
                            formOnly("te", "电气时间常数", "ms").withAliases(A_TE).withSymbol("Te"),
                            bothRequired("iCont", "持续电流 @100°C", "Arms").withAliases(A_ICONT).withSymbol("Icn"),
                            bothRequired("iPeak", "峰值电流", "Arms").withAliases(A_IPEAK).withSymbol("Ipk"),
                            formOnly("pn", "持续热功率 @100°C", "W").withAliases(A_PN).withSymbol("Pcn"),
                            formOnly("tMax", "最高线圈温度", "°C").withAliases(A_TMAX).withSymbol("Tmax"),
                            formOnlyRequired("kth", "热耗散常数", "W/°C").withAliases(A_KTH).withSymbol("Kth"),
                            formOnlyRequired("uBus", "最高母线电压", "Vdc").withAliases(A_UBUS).withSymbol("Ubus"),
                            formOnly("poles", "极数", "-").withAliases(A_POLES).withSymbol("2p"),
                            // 第 66 轮：不再是必填。样册口径并不统一——AXD 系列写「最高转速 @ 持续扭矩（600V）」，
                            // AXM 系列只给一行「最高转速」，按必填处理会让整批力矩型号在入库预览里全部「跳过」。
                            // 缺该项只跳过准入②并注明「未校核」，与直线/音圈同口径（第 43/44 轮）。
                            formOnly("maxSpeedPeak", "最高转速 @峰值转矩", "rpm").withAliases(A_MAX_SPEED_PEAK_T).withSymbol("Ωmax"),
                            formOnly("maxSpeedCont", "最高转速 @持续转矩", "rpm").withAliases(A_MAX_SPEED_CONT_T).withSymbol("Ωmax")
                    )),
                    new MotorFieldGroup(GROUP_MECHANICAL, false, List.of(
                            formOnly("totalMass", "总质量", "kg").withAliases(A_TOTAL_MASS),
                            both("inertia", "转动惯量", "kg·m²").withAliases(A_INERTIA),
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
                            both("stroke", "行程", "mm").withAliases(A_STROKE),
                            bothRequired("forceCont", "持续推力 @100°C", "N").withAliases(A_FORCE_CONT).withSymbol("Fcn"),
                            bothRequired("forcePeak", "峰值推力", "N").withAliases(A_FORCE_PEAK).withSymbol("Fpk"),
                            bothRequired("kf", "力常数 ±10%", "N/A").withAliases(A_KF).withSymbol("Kf"),
                            bothRequired("ke", "反电势常数 ±10%", "V/(m/s)").withAliases(A_KE).withSymbol("Ke"),
                            both("km", "电机常数 @25°C", "N/SqRt(W)").withAliases(A_KM).withSymbol("Km"),
                            bothRequired("r25", "电阻 @25°C ±10%", "Ω").withAliases(A_R25).withSymbol("R25"),
                            both("l", "电感 ±20%", "mH").withAliases(A_L).withSymbol("L"),
                            formOnly("te", "电气时间常数", "ms").withAliases(A_TE).withSymbol("Te"),
                            bothRequired("iCont", "持续电流 @100°C", "A").withAliases(A_ICONT).withSymbol("Icn"),
                            bothRequired("iPeak", "峰值电流", "A").withAliases(A_IPEAK).withSymbol("Ipk"),
                            formOnly("pn", "持续热功率 @100°C", "W").withAliases(A_PN).withSymbol("Pcn"),
                            formOnly("tMax", "最高线圈温度", "°C").withAliases(A_TMAX).withSymbol("Tmax"),
                            formOnlyRequired("kth", "热耗散常数", "W/°C").withAliases(A_KTH).withSymbol("Kth"),
                            formOnlyRequired("uBus", "最高电压", "Vdc").withAliases(A_UBUS).withSymbol("Ubus")
                    )),
                    new MotorFieldGroup(GROUP_MECHANICAL, false, List.of(
                            // 音圈机械参数按 g 录入，内部统一按 kg 计算（第 63 轮 Q4）
                            both("moverMass", "线圈质量", "g", new BigDecimal("0.001")).withAliases(A_MOVER_MASS),
                            both("coreMass", "铁芯质量", "g", new BigDecimal("0.001")).withAliases(A_CORE_MASS),
                            both("gap", "运动间隙", "mm").withAliases(A_GAP)
                    )),
                    new MotorFieldGroup(GROUP_ADOPTION, true, List.of(
                            formOnly("maxSpeedPeak", "最高速度 @峰值推力", "m/s").withAliases(A_MAX_SPEED_PEAK_L),
                            formOnly("maxSpeedCont", "最高速度 @持续推力", "m/s").withAliases(A_MAX_SPEED_CONT_L)
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
