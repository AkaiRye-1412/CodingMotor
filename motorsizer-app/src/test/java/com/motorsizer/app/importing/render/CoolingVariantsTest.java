package com.motorsizer.app.importing.render;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 冷却变体拆分口径（2026-11-19 用户裁定）。
 *
 * <p>核心不是「能拆」，而是三件事必须对：
 * ① 只有气冷/水冷标注的行归各自型号，自冷与未标注归基型；
 * ② 不带标注的通用参数三个型号都带；
 * ③ 某个参数只有气冷值、没有水冷值时，水冷型号<b>留空</b>，不能拿气冷的值顶替。
 */
class CoolingVariantsTest {

    /** 参数名里的冷却标注（样册写法），连同英文图纸写法一起认。 */
    @Test
    void recognizesCoolingLabels() {
        assertThat(CoolingVariants.classify("持续电流（自冷）@100℃"))
                .isEqualTo(CoolingVariants.Variant.SELF);
        assertThat(CoolingVariants.classify("持续电流（气冷）@100℃"))
                .isEqualTo(CoolingVariants.Variant.AIR);
        assertThat(CoolingVariants.classify("持续电流(水冷)@100℃"))
                .isEqualTo(CoolingVariants.Variant.WATER);

        // 同义写法
        assertThat(CoolingVariants.classify("持续电流（自然冷却）@100℃"))
                .isEqualTo(CoolingVariants.Variant.SELF);
        assertThat(CoolingVariants.classify("持续电流（强制风冷）@100℃"))
                .isEqualTo(CoolingVariants.Variant.AIR);
        assertThat(CoolingVariants.classify("持续电流（液冷）@100℃"))
                .isEqualTo(CoolingVariants.Variant.WATER);

        // 客户图纸是英文
        assertThat(CoolingVariants.classify("Continuous Current (Water)"))
                .isEqualTo(CoolingVariants.Variant.WATER);
        assertThat(CoolingVariants.classify("Continuous Current (Air Cooled)"))
                .isEqualTo(CoolingVariants.Variant.AIR);
        assertThat(CoolingVariants.classify("Self-cooled Continuous Current"))
                .isEqualTo(CoolingVariants.Variant.SELF);

        // AKM 图纸只写缩写：WC = Water Cooling（用户确认），AC = Air Cooling
        assertThat(CoolingVariants.classify("Continuous Force (WC) @100°C"))
                .isEqualTo(CoolingVariants.Variant.WATER);
        assertThat(CoolingVariants.classify("Thermal Dissipation Constant (AC)"))
                .isEqualTo(CoolingVariants.Variant.AIR);
    }

    /** 不带标注的参数、以及「水冷流量」这类参数本身带冷却字样的，都不能被当成立变体标签。 */
    @Test
    void keepsPlainParametersUnlabelled() {
        assertThat(CoolingVariants.classify("峰值电流")).isEqualTo(CoolingVariants.Variant.NONE);
        assertThat(CoolingVariants.classify("")).isEqualTo(CoolingVariants.Variant.NONE);
        assertThat(CoolingVariants.classify(null)).isEqualTo(CoolingVariants.Variant.NONE);
        // 「水冷」是参数名的一部分（冷却水流量），不是型号变体
        assertThat(CoolingVariants.classify("水冷流量")).isEqualTo(CoolingVariants.Variant.NONE);
        assertThat(CoolingVariants.classify("Water Flow (L/min)"))
                .isEqualTo(CoolingVariants.Variant.NONE);
    }

    /** 三种冷却并排时拆成三个型号：各自的标注行 + 通用行，参数名保持原样。 */
    @Test
    void splitsIntoThreeModels() {
        CatalogTable table = table("AUM4-S1-S", List.of(
                row("持续电流（自冷）@100℃", "Icn", "Arms", "2.3"),
                row("持续电流（气冷）@100℃", "Ica", "Arms", "2.8"),
                row("持续电流（水冷）@100℃", "Icw", "Arms", "3.0"),
                row("峰值电流", "Ipk", "Arms", "13.0")));

        List<CatalogTable> parts = CoolingVariants.split(table);

        assertThat(parts).extracting(CatalogTable::outputModel)
                .containsExactly("AUM4-S1-S", "AUM4-S1-S-A", "AUM4-S1-S-W");
        assertThat(names(parts.get(0)))
                .containsExactly("持续电流（自冷）@100℃", "峰值电流");
        assertThat(names(parts.get(1)))
                .containsExactly("持续电流（气冷）@100℃", "峰值电流");
        assertThat(names(parts.get(2)))
                .containsExactly("持续电流（水冷）@100℃", "峰值电流");
        // 参数名保留原样，与样册逐字一致
        assertThat(names(parts.get(2)).get(0)).contains("（水冷）");
    }

    /** 只有气冷值、没有水冷值时，水冷型号该项留空——不拿气冷的值顶替。 */
    @Test
    void doesNotBorrowMissingCoolingValues() {
        CatalogTable table = table("AUM4-S1-S", List.of(
                row("持续电流（气冷）@100℃", "Ica", "Arms", "2.8"),
                row("持续电流（水冷）@100℃", "Icw", "Arms", "3.0"),
                row("持续热功率（气冷）@100℃", "Pca", "W", "69")));

        List<CatalogTable> parts = CoolingVariants.split(table);

        assertThat(parts).extracting(CatalogTable::outputModel)
                .containsExactly("AUM4-S1-S-A", "AUM4-S1-S-W");
        assertThat(names(parts.get(0)))
                .containsExactly("持续电流（气冷）@100℃", "持续热功率（气冷）@100℃");
        // 水冷型号没有「持续热功率（水冷）」这一行：留空，不借气冷的值
        assertThat(names(parts.get(1))).containsExactly("持续电流（水冷）@100℃");
    }

    /**
     * 线圈质量与冷却无关：样册只给一行「线圈质量（自冷）」时，气冷/水冷型号也要带上
     * （2026-11-20 用户指出 AUM3：气冷型号因为「缺少必填参数：线圈质量」整批跳过）。
     */
    @Test
    void sharesCoilMassAcrossCoolingVariants() {
        CatalogTable table = table("AUM3-S1-S", List.of(
                row("持续推力（自冷）@100℃", "Fcn", "N", "28"),
                row("持续推力（气冷）@100℃", "Fca", "N", "34"),
                row("持续推力（水冷）@100℃", "Fcw", "N", "37"),
                row("线圈质量（自冷）", "mcn", "kg", "0.22")));

        List<CatalogTable> parts = CoolingVariants.split(table);

        assertThat(parts).extracting(CatalogTable::outputModel)
                .containsExactly("AUM3-S1-S", "AUM3-S1-S-A", "AUM3-S1-S-W");
        // 三个变体都带「线圈质量」，参数名保留原样
        assertThat(names(parts.get(1))).containsExactly("持续推力（气冷）@100℃", "线圈质量（自冷）");
        assertThat(names(parts.get(2))).containsExactly("持续推力（水冷）@100℃", "线圈质量（自冷）");
        assertThat(parts.get(1).rows().get(1).values()).containsEntry("v0", "0.22");
        // 共用参数不是「这个型号有这种冷却」的证据：只给线圈质量（自冷）不会凭空多出气冷/水冷型号
        CatalogTable massOnly = table("AUM3-S9-S", List.of(
                row("持续推力（自冷）@100℃", "Fcn", "N", "28"),
                row("线圈质量（自冷）", "mcn", "kg", "0.22")));
        assertThat(CoolingVariants.split(massOnly)).extracting(CatalogTable::outputModel)
                .containsExactly("AUM3-S9-S");
    }

    /** 样册真按冷却分别给了质量时（两行都有值），不共用，各变体取自己的值。 */
    @Test
    void keepsPerCoolingMassWhenCatalogProvidesBoth() {
        CatalogTable table = table("AUM3-S1-S", List.of(
                row("持续推力（自冷）@100℃", "Fcn", "N", "28"),
                row("持续推力（气冷）@100℃", "Fca", "N", "34"),
                row("线圈质量（自冷）", "mcn", "kg", "0.22"),
                row("线圈质量（气冷）", "mca", "kg", "0.24")));

        List<CatalogTable> parts = CoolingVariants.split(table);

        assertThat(parts).extracting(CatalogTable::outputModel)
                .containsExactly("AUM3-S1-S", "AUM3-S1-S-A");
        assertThat(names(parts.get(0))).containsExactly("持续推力（自冷）@100℃", "线圈质量（自冷）");
        assertThat(names(parts.get(1))).containsExactly("持续推力（气冷）@100℃", "线圈质量（气冷）");
        assertThat(parts.get(1).rows().get(1).values()).containsEntry("v0", "0.24");
    }

    /** 只有气冷/水冷数据、没有自冷数据时：不出基型（否则会多一台缺持续参数的残型号）。 */
    @Test
    void skipsBaseModelWhenNoSelfCooledData() {
        CatalogTable table = table("AKM100-B2-S", List.of(
                row("持续电流（水冷）@100℃", "Icw", "Arms", "8.2"),
                row("峰值电流", "Ipk", "Arms", "24.0")));

        List<CatalogTable> parts = CoolingVariants.split(table);

        assertThat(parts).extracting(CatalogTable::outputModel).containsExactly("AKM100-B2-S-W");
        assertThat(names(parts.get(0))).containsExactly("持续电流（水冷）@100℃", "峰值电流");
    }

    /** 型号里已经带 {@code -W-} 段的不再追加后缀（AKM/AKH 的水冷型号本来就叫 -W）。 */
    @Test
    void doesNotDoubleCoolingSuffix() {
        assertThat(CoolingVariants.appendSuffix("AKM100-W-B2-S", CoolingVariants.Variant.WATER))
                .isEqualTo("AKM100-W-B2-S");
        assertThat(CoolingVariants.appendSuffix("AKH100-W-B2-H", CoolingVariants.Variant.WATER))
                .isEqualTo("AKH100-W-B2-H");
        assertThat(CoolingVariants.appendSuffix("AUM4-S1-S", CoolingVariants.Variant.WATER))
                .isEqualTo("AUM4-S1-S-W");
        assertThat(CoolingVariants.appendSuffix("AUM4-S1-S", CoolingVariants.Variant.AIR))
                .isEqualTo("AUM4-S1-S-A");
        // 已经以 -W 结尾的不再叠一层
        assertThat(CoolingVariants.appendSuffix("ABC-100-W", CoolingVariants.Variant.WATER))
                .isEqualTo("ABC-100-W");
    }

    /** 没有自冷数据、也没有通用参数时，不出一个只剩空壳的基型。 */
    @Test
    void skipsEmptyBaseModel() {
        CatalogTable table = table("AUM4-S1-S", List.of(
                row("持续电流（气冷）@100℃", "Ica", "Arms", "2.8"),
                row("持续电流（水冷）@100℃", "Icw", "Arms", "3.0")));

        assertThat(CoolingVariants.split(table)).extracting(CatalogTable::outputModel)
                .containsExactly("AUM4-S1-S-A", "AUM4-S1-S-W");
    }

    /** 完全没有冷却标注的表原样返回（同一个对象），不给常规样册增加任何开销。 */
    @Test
    void leavesUnlabelledTableUntouched() {
        CatalogTable table = table("AXD120-71", List.of(
                row("持续转矩 @100°C", "Tcn", "Nm", "3.4"),
                row("峰值转矩", "Tpk", "Nm", "10.0")));

        assertThat(CoolingVariants.split(table)).containsExactly(table);
    }

    /** 只有自冷行时：不新增型号，基型收下全部行。 */
    @Test
    void selfCooledOnlyStaysOneModel() {
        CatalogTable table = table("AUM4-S1-S", List.of(
                row("持续电流（自冷）@100℃", "Icn", "Arms", "2.3"),
                row("峰值电流", "Ipk", "Arms", "13.0")));

        List<CatalogTable> parts = CoolingVariants.split(table);

        assertThat(parts).hasSize(1);
        assertThat(parts.get(0).model()).isEqualTo("AUM4-S1-S");
        assertThat(names(parts.get(0))).hasSize(2);
    }

    /**
     * 样册里没有值的列不新增变体型号（2026-11-19 用户裁定）。
     *
     * <p>AUM5 一张表并排十几个型号，S1 给了气冷/水冷值，S10 那两行写的是 {@code -}。
     * 按旧口径 S10 也会被塞进 -A / -W 表，生成缺「持续推力/持续电流」的残型号，导入时只能跳过。
     */
    @Test
    void doesNotSpawnVariantForColumnsWithoutValues() {
        CatalogTable table = wide("AUM5",
                List.of("AUM5-S1串联", "AUM5-S1并联", "AUM5-S10串联", "AUM5-S10并联"),
                List.of(
                        row("持续推力（自冷）@100℃", "Fcn", "N", "118", "97", "5659", "5637"),
                        row("持续推力（气冷）@100℃", "Fca", "N", "236", "197", "-", "-"),
                        row("持续推力（水冷）@100℃", "Fcw", "N", "255", "255", "-", "-"),
                        row("峰值推力", "Fpk", "N", "399", "333", "786", "884")));

        List<CatalogTable> parts = CoolingVariants.split(table);

        assertThat(parts).extracting(CatalogTable::outputModel)
                .containsExactly("AUM5", "AUM5-A", "AUM5-W");
        // 基型收全部四列；气冷/水冷只剩 S1 的两列
        assertThat(parts.get(0).labels()).hasSize(4);
        assertThat(parts.get(1).labels()).containsExactly("AUM5-S1串联", "AUM5-S1并联");
        assertThat(parts.get(2).labels()).containsExactly("AUM5-S1串联", "AUM5-S1并联");
        // 通用行「峰值推力」在 S10 也有值，但通用行不是「这个型号有气冷」的证据
        assertThat(parts.get(1).rows().get(1).values())
                .containsExactly(Map.entry("v0", "399"), Map.entry("v1", "333"));
    }

    /** 气冷/水冷行整表都是 {@code -} 时，一个变体型号都不新增。 */
    @Test
    void skipsVariantWhenEveryColumnIsBlank() {
        CatalogTable table = wide("AUM5-S12", List.of("串联", "并联"), List.of(
                row("持续推力（自冷）@100℃", "Fcn", "N", "5659", "5637"),
                row("持续推力（气冷）@100℃", "Fca", "N", "-", "-"),
                row("持续推力（水冷）@100℃", "Fcw", "N", "—", "/")));

        assertThat(CoolingVariants.split(table)).extracting(CatalogTable::outputModel)
                .containsExactly("AUM5-S12");
    }

    /** 投影后值列要重新编号：剪掉中间的列不会让后面的列错位串值。 */
    @Test
    void renumbersColumnsAfterProjection() {
        CatalogTable table = wide("AXD80",
                List.of("A1串联", "A1并联", "A2串联", "A2并联", "A3串联", "A3并联"),
                List.of(
                        row("持续电流（自冷）@100℃", "Icn", "Arms", "1", "2", "-", "-", "5", "6"),
                        row("持续电流（气冷）@100℃", "Ica", "Arms", "-", "-", "-", "-", "7", "8")));

        List<CatalogTable> parts = CoolingVariants.split(table);
        CatalogTable air = parts.stream()
                .filter(t -> t.variant() == CoolingVariants.Variant.AIR)
                .findFirst().orElseThrow();

        assertThat(air.labels()).containsExactly("A3串联", "A3并联");
        assertThat(air.rows().get(0).values())
                .containsExactly(Map.entry("v0", "7"), Map.entry("v1", "8"));
    }

    /** 入库用：一个型号的数据行整体对应哪种冷却（自冷/未标注返回 null，由模板给「普通」）。 */
    @Test
    void reportsCoolingLabel() {
        assertThat(CoolingVariants.coolingLabel(List.of("持续电流（水冷）@100℃", "峰值电流")))
                .isEqualTo("水冷");
        assertThat(CoolingVariants.coolingLabel(List.of("持续电流（气冷）@100℃")))
                .isEqualTo("气冷");
        assertThat(CoolingVariants.coolingLabel(List.of("持续电流（自冷）@100℃"))).isNull();
        assertThat(CoolingVariants.coolingLabel(List.of("峰值电流"))).isNull();
    }

    // ---------------------------------------------------------------- 夹具

    private static CatalogTable table(String model, List<TableRow> rows) {
        return new CatalogTable(3, model, List.of("值"), List.of("值"), rows);
    }

    /** 多列版式：一列一个「型号+接法」。列数是判「有没有这种冷却」的粒度。 */
    private static CatalogTable wide(String model, List<String> labels, List<TableRow> rows) {
        return new CatalogTable(5, model, labels, labels, rows);
    }

    private static List<String> names(CatalogTable table) {
        return table.rows().stream().map(TableRow::name).toList();
    }

    private static TableRow row(String name, String sym, String unit, String... values) {
        TableRow r = new TableRow();
        r.name = name;
        r.sym = sym;
        r.unit = unit;
        for (int i = 0; i < values.length; i++) {
            r.values.put("v" + i, values[i]);
        }
        return r;
    }
}
