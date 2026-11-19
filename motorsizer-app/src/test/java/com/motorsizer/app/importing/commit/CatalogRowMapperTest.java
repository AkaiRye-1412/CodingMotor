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

    private static CatalogSummarySheet.SheetRow row(String model, String section, String name,
                                                    String symbol, String unit, String value) {
        return new CatalogSummarySheet.SheetRow(model, section, name, symbol, unit, value);
    }
}
