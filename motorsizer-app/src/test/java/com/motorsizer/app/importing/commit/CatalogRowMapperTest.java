package com.motorsizer.app.importing.commit;

import com.motorsizer.domain.catalog.MotorType;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 「参数汇总」行 → 入库请求的映射口径（06 §6.1）。
 *
 * <p>不依赖真 PDF、也不依赖 Spring：只喂合成行，锁住四条规则——
 * 符号优先、按命中数定类型、{@code -S}/{@code -P} 拆主键、必填缺失不补 0。
 */
class CatalogRowMapperTest {

    private final CatalogRowMapper mapper = new CatalogRowMapper();

    /** 力矩样册：{@code Tcn/Tpk/...} 只有力矩模板有；两行 {@code Ωmax} 必须靠参数名区分。 */
    @Test
    void mapsTorqueCatalogAndSplitsSeriesConfig() {
        List<CatalogSummarySheet.SheetRow> rows = List.of(
                row("AXD120-71-S", "性能参数", "持续转矩 @100°C", "Tcn", "Nm", "3.4"),
                row("AXD120-71-S", "性能参数", "峰值转矩", "Tpk", "Nm", "10.0"),
                row("AXD120-71-S", "性能参数", "转矩常数 ±10%", "Kt", "Nm/Arms", "3.0"),
                row("AXD120-71-S", "性能参数", "反电势常数 ±10%", "Ke", "Vpeak/rpm", "62"),
                row("AXD120-71-S", "性能参数", "相间电阻 @25°C ±10%", "R25", "Ω", "1.92"),
                row("AXD120-71-S", "性能参数", "持续电流 @100°C", "Icn", "Arms", "3.8"),
                row("AXD120-71-S", "性能参数", "峰值电流", "Ipk", "Arms", "12.2"),
                row("AXD120-71-S", "性能参数", "最高线圈温度", "Tmax", "°C", "100"),
                row("AXD120-71-S", "性能参数", "热耗散常数", "Kth", "W/°C", "0.715"),
                row("AXD120-71-S", "性能参数", "最高母线电压", "Ubus", "Vdc", "600"),
                row("AXD120-71-S", "性能参数", "最高转速 @峰值转矩", "Ωmax", "rpm", "1700"),
                row("AXD120-71-S", "性能参数", "最高转速 @持续转矩", "Ωmax", "rpm", "2000"));

        List<CatalogRowMapper.MappedMotor> motors = mapper.map(rows);

        assertThat(motors).hasSize(1);
        CatalogRowMapper.MappedMotor motor = motors.get(0);
        assertThat(motor.type()).isEqualTo(MotorType.TORQUE);
        assertThat(motor.modelName()).as("型号名以 Excel 为准，不拆 -S").isEqualTo("AXD120-71-S");
        assertThat(motor.configName()).isEqualTo("默认");
        assertThat(motor.missing()).isEmpty();
        // Ωmax 出现两次：靠 @峰值/@持续 分别落到两行，不取同一个值
        assertThat(motor.spec()).containsEntry("maxSpeedPeak", new BigDecimal("1700"))
                .containsEntry("maxSpeedCont", new BigDecimal("2000"))
                .containsEntry("torqueCont", new BigDecimal("3.4"));
    }

    /** 音圈样册：{@code 行程} 只有音圈模板有；质量按 g 录入、内部存 kg。 */
    @Test
    void mapsCoilCatalogAndConvertsGramToKilogram() {
        List<CatalogSummarySheet.SheetRow> rows = List.of(
                row("AVM12-6.4", "性能参数", "行程", "Stroke", "mm", "8"),
                row("AVM12-6.4", "性能参数", "持续推力 @100°C", "Fcn", "N", "20"),
                row("AVM12-6.4", "性能参数", "峰值推力", "Fpk", "N", "63"),
                row("AVM12-6.4", "性能参数", "力常数 ±10%", "Kf", "N/A", "5.2"),
                row("AVM12-6.4", "性能参数", "反电势常数 ±10%", "Ke", "V/(m/s)", "5.2"),
                row("AVM12-6.4", "性能参数", "电阻 @25°C ±10%", "R25", "Ω", "2.7"),
                row("AVM12-6.4", "性能参数", "持续电流 @100°C", "Icn", "A", "6.0"),
                row("AVM12-6.4", "性能参数", "峰值电流", "Ipk", "A", "18"),
                row("AVM12-6.4", "性能参数", "最高线圈温度", "Tmax", "°C", "100"),
                row("AVM12-6.4", "性能参数", "热耗散常数", "Kth", "W/°C", "1.5"),
                row("AVM12-6.4", "性能参数", "最高电压", "Ubus", "Vdc", "600"),
                row("AVM12-6.4", "性能参数", "线圈质量", "", "g", "45"));

        CatalogRowMapper.MappedMotor motor = mapper.map(rows).get(0);

        assertThat(motor.type()).isEqualTo(MotorType.COIL);
        assertThat(motor.configName()).isEqualTo("默认");
        assertThat(motor.missing()).isEmpty();
        assertThat(motor.spec()).containsEntry("stroke", new BigDecimal("8"))
                // 45 g → 0.045 kg（K2 factor 0.001），差 1000 倍会直接影响惯量计算
                .containsEntry("moverMass", new BigDecimal("0.045"));
    }

    /**
     * 样册把「转矩」写成「扭矩」并加电压后缀时仍要认出来。
     *
     * <p>Direct Drive Motors 的实际写法是 {@code 最高转速 @ 持续扭矩（600V）}；
     * 别名里只有「转矩」时这两行会被丢掉——页面上看起来「样册没给转速」，
     * 实际是没匹配上，导入后准入②永远「未校核」。
     */
    @Test
    void recognisesTorqueWordingWithVoltageSuffix() {
        List<CatalogSummarySheet.SheetRow> rows = List.of(
                row("AXD200-77-S", "性能参数", "持续转矩(自冷)@100ºC", "Tcn", "Nm", "7.0"),
                row("AXD200-77-S", "性能参数", "峰值转矩", "Tpk", "Nm", "21.0"),
                row("AXD200-77-S", "性能参数", "最高转速 @ 持续扭矩（600V）", "Ωmax", "rpm", "1700"),
                row("AXD200-77-S", "性能参数", "最高转速 @ 峰值扭矩（600V）", "Ωmax", "rpm", "1400"));

        CatalogRowMapper.MappedMotor motor = mapper.map(rows).get(0);

        assertThat(motor.type()).isEqualTo(MotorType.TORQUE);
        assertThat(motor.spec())
                .containsEntry("maxSpeedCont", new BigDecimal("1700"))
                .containsEntry("maxSpeedPeak", new BigDecimal("1400"));
    }

    /** 必填缺失只标记跳过，不用 0 占位——写进库的每台都必须过 C3 校验。 */
    @Test
    void reportsMissingRequiredFieldsInsteadOfFillingZeros() {
        List<CatalogSummarySheet.SheetRow> rows = List.of(
                row("CRM0013", "性能参数", "持续转矩 @100°C", "Tcn", "Nm", "11.0"),
                row("CRM0013", "性能参数", "峰值转矩", "Tpk", "Nm", "32.9"));

        CatalogRowMapper.MappedMotor motor = mapper.map(rows).get(0);

        assertThat(motor.type()).isEqualTo(MotorType.TORQUE);
        assertThat(motor.missing()).isNotEmpty()
                .contains("转矩常数 ±10%", "最高母线电压");
        assertThat(motor.spec()).doesNotContainKey("kt");
    }

    /** 值列的三类写法：带单位后缀、千分位、非数值占位。 */
    @Test
    void parsesMessyValues() {
        assertThat(CatalogRowMapper.parseNumber("1,700")).isEqualByComparingTo("1700");
        assertThat(CatalogRowMapper.parseNumber("<0.5")).isEqualByComparingTo("0.5");
        assertThat(CatalogRowMapper.parseNumber("1.18E-03")).isEqualByComparingTo("0.00118");
        assertThat(CatalogRowMapper.parseNumber("-")).isNull();
        assertThat(CatalogRowMapper.parseNumber("N/A")).isNull();
    }

    /**
     * 型号列 → 主键：**型号名与 Excel 逐字一致**，配置名恒为「默认」。
     *
     * <p>曾经把 {@code -S}/{@code -P} 拆成「型号 + 串联/并联」，结果 Excel 上写着
     * {@code AXD120-71-S}、库里却是 {@code AXD120-71 / 串联}，用户拿 Excel 查不到自己的电机。
     * 第 65 轮裁定改为原样入库。
     */
    @Test
    void keepsModelNameExactlyAsWrittenInExcel() {
        assertThat(CatalogRowMapper.keyOf("AXD120-71-S")).containsExactly("AXD120-71-S", "默认");
        assertThat(CatalogRowMapper.keyOf("AXD120-71-P")).containsExactly("AXD120-71-P", "默认");
        assertThat(CatalogRowMapper.keyOf("AKM100-B4/D79")).containsExactly("AKM100-B4/D79", "默认");
        // 中文接法也一样：解析阶段已压成 -S/-P，这里不再二次加工
        assertThat(CatalogRowMapper.keyOf(" AXD120-71-S ")).containsExactly("AXD120-71-S", "默认");
    }

    /**
     * 按冷却方式拆出的型号：入库时「冷却方式」跟着型号走。
     *
     * <p>样册一款电机按冷却方式拆成 {@code -A} / {@code -W} 之后，三台电机若都写「普通」，
     * 电机库里就分不出谁能水冷；自冷/未标注仍是模板默认的「普通」。
     */
    @Test
    void fillsCoolingFromCoolingVariants() {
        List<CatalogRowMapper.MappedMotor> motors = mapper.map(List.of(
                row("AUM4-S1-S", "性能参数", "持续电流（自冷）@100℃", "Icn", "Arms", "2.3"),
                row("AUM4-S1-S-A", "性能参数", "持续电流（气冷）@100℃", "Ica", "Arms", "2.8"),
                row("AUM4-S1-S-W", "性能参数", "持续电流（水冷）@100℃", "Icw", "Arms", "3.0")));

        assertThat(motors).extracting(CatalogRowMapper.MappedMotor::modelName)
                .containsExactly("AUM4-S1-S", "AUM4-S1-S-A", "AUM4-S1-S-W");
        assertThat(motors).extracting(CatalogRowMapper.MappedMotor::cooling)
                .containsExactly("普通", "气冷", "水冷");
    }

    /**
     * 样册不采「最高线圈温度」：入库补默认 100 °C，并在 notes 里留痕。
     *
     * <p>采集口径剔掉了这一行，可热校核（允许电流 / 是否超温）离不开它；用户裁定按默认 100 °C。
     * 补值而不是留空——否则电机库、报告、界面三个地方看到的数不一样。
     */
    @Test
    void fillsDefaultMaxCoilTempWhenCatalogOmitsIt() {
        List<CatalogSummarySheet.SheetRow> rows = List.of(
                row("AUM4-S1-S", "性能参数", "持续推力（自冷）@100℃", "Fcn", "N", "55"),
                row("AUM4-S1-S", "性能参数", "峰值推力", "Fpk", "N", "165"),
                row("AUM4-S1-S", "性能参数", "力常数 ±10%", "Kf", "N/Arms", "23.9"),
                row("AUM4-S1-S", "性能参数", "反电势常数 ±10%", "Ke", "Vpeak/(m/s)", "20.4"),
                row("AUM4-S1-S", "性能参数", "相间电阻 @25℃ ±10%", "R25", "Ω", "24"),
                row("AUM4-S1-S", "性能参数", "持续电流（自冷）@100℃", "Icn", "Arms", "2.3"),
                row("AUM4-S1-S", "性能参数", "峰值电流", "Ipk", "Arms", "13.0"),
                row("AUM4-S1-S", "性能参数", "热耗散常数（自冷）", "Kth", "W/℃", "0.55"),
                row("AUM4-S1-S", "性能参数", "最高母线电压", "Ubus", "Vdc", "330/600"));

        CatalogRowMapper.MappedMotor motor = mapper.map(rows).get(0);

        assertThat(motor.spec().get("tMax")).isEqualByComparingTo("100");
        assertThat(motor.notes()).anyMatch(note -> note.contains("最高线圈温度") && note.contains("100"));
    }

    /**
     * 水冷表（英文图纸，符号带 {@code w} 尾字母）：热耗散常数等照样要认出来。
     *
     * <p>AKM200 的真实现象：{@code Kthw} 对不上模板里的 {@code Kth}，整行丢弃 →
     * 「缺少必填参数：热耗散常数」→ 一台明明参数齐全的水冷电机被跳过。同一张表里
     * {@code Thermal Dissipation Constant} 还被 {@code Ip}（⊂ dissi<b>p</b>ation）撞成
     * 「{@code kth} / {@code iPeak} 二选一不确定」，{@code Magnetic Period} 被
     * {@code Ic}（⊂ magnet<b>ic</b>）撞成持续电流——三个字段一起丢。
     */
    @Test
    void mapsWaterCooledTableWithCoolingSuffixedSymbols() {
        String model = "AKM200-A-B4-W-B";
        List<CatalogSummarySheet.SheetRow> rows = List.of(
                row(model, "性能参数", "Continuous Force (WC) @100°C", "Few", "N", "3489"),
                row(model, "性能参数", "Peak Force", "Fpk", "N", "10467"),
                row(model, "性能参数", "Force Constant ±10%", "Kf", "N/Arms", "446"),
                row(model, "性能参数", "Back EMF Constant ±10%", "Ke", "V/(m/s)", "446"),
                row(model, "性能参数", "Phase Resistance @25°C ±10%", "R25", "Ω", "0.62"),
                row(model, "性能参数", "Continuous Current (WC) @100°C", "Icw", "Arms", "16"),
                row(model, "性能参数", "Peak Current", "Ipk", "Arms", "48"),
                row(model, "性能参数", "Continuous Power Dissipation (WC)", "Pcw", "W", "1485"),
                row(model, "性能参数", "Thermal Dissipation Constant (WC)", "Kthw", "W/°C", "19.8"),
                row(model, "性能参数", "Magnetic Period", "", "mm", "36"),
                row(model, "性能参数", "Maximum Bus Voltage", "Ubus", "Vdc", "600"));

        CatalogRowMapper.MappedMotor motor = mapper.map(rows).get(0);

        assertThat(motor.type()).isEqualTo(MotorType.LINEAR);
        assertThat(motor.missing()).isEmpty();
        assertThat(motor.spec())
                .containsEntry("kth", new BigDecimal("19.8"))
                .containsEntry("iCont", new BigDecimal("16"))
                .containsEntry("pn", new BigDecimal("1485"))
                .containsEntry("forceCont", new BigDecimal("3489"))
                .containsEntry("polePitch", new BigDecimal("36"));
        // 冷却方式跟参数表走：名字里是英文 (WC)，型号结尾是 -B，只有参数表认得出来
        assertThat(motor.cooling()).isEqualTo("水冷");
    }

    /**
     * 短别名不许在别的单词里「碰运气」。
     *
     * <p>{@code Ip} 是 {@code dissipation} 的子串、{@code Ic} 是 {@code magnetic} 的子串。
     * 一旦按子串算，热耗散常数会同时命中 {@code kth} 与 {@code iPeak}，
     * {@code unique()} 判「不确定」返回 {@code null}，整行消失。
     */
    @Test
    void shortAliasesMustNotMatchInsideUnrelatedWords() {
        List<CatalogSummarySheet.SheetRow> rows = List.of(
                row("AKM200-A-B4-W-B", "性能参数", "Thermal Dissipation Constant (WC)", "Kthw", "W/°C", "19.8"),
                row("AKM200-A-B4-W-B", "性能参数", "Magnetic Period", "", "mm", "36"),
                row("AKM200-A-B4-W-B", "性能参数", "Peak Current", "Ipk", "Arms", "48"));

        CatalogRowMapper.MappedMotor motor = mapper.map(rows).get(0);

        assertThat(motor.spec()).containsEntry("kth", new BigDecimal("19.8"))
                .containsEntry("polePitch", new BigDecimal("36"))
                .containsEntry("iPeak", new BigDecimal("48"));
        assertThat(motor.spec()).doesNotContainKey("iCont");
    }

    /**
     * 参数名里没有任何冷却字样时，型号结尾的 {@code -A} / {@code -W} 兜底。
     *
     * <p>但只看<b>结尾</b>：{@code AKM200-A-B4} 中间的 {@code -A-} 是「A 型」结构代号，
     * 把它当气冷会把普通冷却的电机写错（真库 12 台 RDM 就是这样）。
     */
    @Test
    void fallsBackToModelSuffixForCoolingButIgnoresStructureCode() {
        List<CatalogRowMapper.MappedMotor> motors = mapper.map(List.of(
                row("AUM4-S1-S-W", "性能参数", "Continuous Force @100°C", "Fcn", "N", "72"),
                row("AUM4-S1-S-A", "性能参数", "Continuous Force @100°C", "Fcn", "N", "66"),
                row("AKM200-A-B4", "性能参数", "Continuous Force @100°C", "Fcn", "N", "3000")));

        assertThat(motors).extracting(CatalogRowMapper.MappedMotor::cooling)
                .containsExactly("水冷", "气冷", "普通");
    }

    /**
     * ACR 弧形电机：样册归在「直线电机」章节、参数表里也带「磁吸力 / 电磁周期 / 线圈质量」
     * 这些直线电机才有的行，但参数全是转矩（{@code Tcn 19.8 Nm}）。
     *
     * <p>按「命中字段数最多」判定时，直线模板与力矩模板各命中 13 项打平、取靠前的 LINEAR 胜出，
     * 于是入库去找「持续推力 / 峰值推力」，ACR240/335/820/1240/1525 全系列被判「缺少必填参数」
     * 整批跳过（2026-11-20 用户截图）。转矩类参数是旋转电机的决定性证据，必须先判 TORQUE。
     */
    @Test
    void detectsArcMotorAsTorqueDespiteLinearOnlyRows() {
        List<CatalogSummarySheet.SheetRow> rows = List.of(
                row("ACR240-S5-S", "性能参数", "持续转矩(自冷) @100°C", "Tcn", "Nm", "19.8"),
                row("ACR240-S5-S", "性能参数", "峰值转矩", "Tpk", "Nm", "72.8"),
                row("ACR240-S5-S", "性能参数", "转矩常数 ±10%", "Kt", "Nm/Arms", "19.77"),
                row("ACR240-S5-S", "性能参数", "反电势常数 ±10%", "Ke", "Vpeak/rpm", "1.69"),
                row("ACR240-S5-S", "性能参数", "电机常数@25°C", "Km", "Nm/Sqrt(W)", "2.8"),
                row("ACR240-S5-S", "性能参数", "相间电阻@25°C ±10%", "R25", "Ω", "35.2"),
                row("ACR240-S5-S", "性能参数", "相间电感 ±30%", "L", "mH", "19.5"),
                row("ACR240-S5-S", "性能参数", "电气时间常数", "τe", "ms", "0.6"),
                row("ACR240-S5-S", "性能参数", "持续电流(自冷) @100°C", "Icn", "Arms", "1.0"),
                row("ACR240-S5-S", "性能参数", "峰值电流", "Ipk", "Arms", "3.7"),
                row("ACR240-S5-S", "性能参数", "持续热功率(自冷) @100°C", "Pcn", "W", "68.0"),
                row("ACR240-S5-S", "性能参数", "热耗散常数(自冷)", "Kthn", "W/°C", "0.9"),
                row("ACR240-S5-S", "性能参数", "最高母线电压", "Ubus", "Vdc", "600"),
                // 下面三行是直线电机独有项，正是它们此前把 ACR 拉到了直线模板
                row("ACR240-S5-S", "性能参数", "电磁周期", "τNN", "degree", "7.2"),
                row("ACR240-S5-S", "性能参数", "磁吸力", "Fa", "kN", "0"),
                row("ACR240-S5-S", "性能参数", "线圈质量 (自冷)", "mcn", "kg", "1.1"));

        CatalogRowMapper.MappedMotor motor = mapper.map(rows).get(0);

        assertThat(motor.type()).isEqualTo(MotorType.TORQUE);
        // 判对类型后必填项齐全，不再「跳过」
        assertThat(motor.missing()).isEmpty();
        assertThat(motor.spec()).containsEntry("torqueCont", new BigDecimal("19.8"))
                .containsEntry("kt", new BigDecimal("19.77"))
                // 第 68 轮：力矩模板也有「线圈质量」，ACR 的 1.1 kg 不再进「未映射的参数」
                .containsEntry("moverMass", new BigDecimal("1.1"));
    }

    private static CatalogSummarySheet.SheetRow row(String model, String section, String name,
                                                    String symbol, String unit, String value) {
        return new CatalogSummarySheet.SheetRow(model, section, name, symbol, unit, value);
    }
}