package com.motorsizer.app.importing.render;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 列归属与「同一物理行被拆成多条基线」的还原规则。
 *
 * <p>这些用例**不依赖真 PDF**：真样册/真图纸用例默认 skip，等于这些形状从来没被测过。
 * 参数取自四份真实图纸踩出来的坑（AKM200 宽单位、Direct Drive 磁吸力、CLA0010 符号断行）。
 */
class ColumnAssignmentTest {

    /** AKM200 第 2 页的实际列边界。 */
    private static CatalogParser.Columns akm() {
        return new CatalogParser.Columns(List.of(
                new CatalogParser.Column("name", 0, 307.1),
                new CatalogParser.Column("sym", 307.1, 370.5),
                new CatalogParser.Column("unit", 370.5, 425.9),
                new CatalogParser.Column("v0", 425.9, 514.4),
                new CatalogParser.Column("v1", 514.4, 603.1)), List.of());
    }

    @Test
    void wideUnitStaysInUnitColumnThoughItsCenterCrosses() {
        // Vpeak/(m/s)：左缘 403 在单位列，中心 429.8 已越到值列
        assertThat(akm().of(TextRun.of("Vpeak/(m/s)", 403.0, 456.6, 270.8, 8.0)))
                .as("宽单位按左缘归列")
                .isEqualTo("unit");
        assertThat(akm().of(TextRun.of("N/Sqrt(W)", 402.0, 449.8, 287.9, 8.0)))
                .isEqualTo("unit");
        // 真正的数值仍在值列
        assertThat(akm().of(TextRun.of("356", 464.9, 479.5, 270.4, 8.0))).isEqualTo("v0");
    }

    @Test
    void rightAlignedShortValueIsNotPushedToTheNextColumn() {
        // Direct Drive 磁吸力：0 右对齐，左缘 205.0 距 v0 右界只有 5.1pt，但它一点都没越界
        CatalogParser.Columns cols = new CatalogParser.Columns(List.of(
                new CatalogParser.Column("sym", 131.0, 153.5),
                new CatalogParser.Column("unit", 153.5, 186.1),
                new CatalogParser.Column("v0", 186.1, 210.1),
                new CatalogParser.Column("v1", 210.1, 234.6)), List.of());
        assertThat(cols.of(TextRun.of("0", 205.0, 208.0, 240.1, 8.0)))
                .as("右对齐的 0 仍在 v0")
                .isEqualTo("v0");
        assertThat(cols.of(TextRun.of("0", 229.8, 232.8, 239.4, 8.0))).isEqualTo("v1");
    }

    @Test
    void symbolWhoseBoxDriftedLeftJoinsTheSymbolColumn() {
        // CLA0010：Fcn 的左缘 490.1 落进参数名列最后 0.6pt，但整段文字主体在符号列
        CatalogParser.Columns cols = new CatalogParser.Columns(List.of(
                new CatalogParser.Column("name", 0, 490.7),
                new CatalogParser.Column("sym", 490.7, 549.8),
                new CatalogParser.Column("unit", 549.8, 636.8),
                new CatalogParser.Column("v0", 636.8, 735.0)), List.of());
        assertThat(cols.of(TextRun.of("Fcn", 490.1, 511.0, 169.4, 8.0)))
                .as("检测框左偏的符号归符号列")
                .isEqualTo("sym");
        assertThat(cols.of(TextRun.of("Continuous Power Dissipation", 230.9, 480.0, 383.2, 8.0)))
                .isEqualTo("name");
    }

    // ------------------------------------------------------------ 行被拆开后的还原

    private static TableRow row(double oy, String name, String sym, String unit, String value) {
        TableRow r = new TableRow();
        r.oy = oy;
        r.name = name;
        r.sym = sym;
        r.unit = unit;
        if (value != null) {
            r.values.put("v0", value);
        }
        return r;
    }

    @Test
    void splitSymbolLandsInTheSymbolColumnInsteadOfBeingGluedIntoValue() {
        // CLA0010 Electrical Time Constant：名字/单位/值在 319.1，符号 Te 单独在 321.5
        CatalogParser parser = new CatalogParser(new PdfPageScanner());
        List<TableRow> rows = new ArrayList<>(List.of(
                row(319.1, "Electrical Time Constant", "", "ms", "0.35"),
                row(321.5, "", "Te", "", null)));

        List<TableRow> out = parser.attachContinuations(rows);

        assertThat(out).hasSize(1);
        TableRow only = out.get(0);
        assertThat(only.sym).as("符号补进符号列").isEqualTo("Te");
        assertThat(only.unit).isEqualTo("ms");
        assertThat(only.values).containsEntry("v0", "0.35");
        assertThat(only.merged).as("没有走『拼字符串』那条路").isFalse();
    }

    @Test
    void oneCharSymbolFragmentYieldsToTheRealSymbol() {
        // CLA0010 Continuous Current：主行符号列被切成 "1"，真正的 Icn 在另一条基线上
        CatalogParser parser = new CatalogParser(new PdfPageScanner());
        List<TableRow> rows = new ArrayList<>(List.of(
                row(340.6, "Continuous Current", "1", "Arms", "2.0"),
                row(344.9, "", "Icn", "", null)));

        List<TableRow> out = parser.attachContinuations(rows);

        assertThat(out).hasSize(1);
        assertThat(out.get(0).sym).isEqualTo("Icn");
        assertThat(out.get(0).values).containsEntry("v0", "2.0");
    }

    @Test
    void wrappedNameIsMergedBackIntoTheParameterRow() {
        // CLA0010 Continuous Power Dissipation：名字 / 值 / 名字 三行，值居中
        CatalogParser parser = new CatalogParser(new PdfPageScanner());
        List<TableRow> rows = new ArrayList<>(List.of(
                row(383.2, "Continuous Power Dissipation (NC", "", "", null),
                row(393.2, "", "Pcn", "W", "40.2"),
                row(402.8, "@100C", "", "", null)));

        List<TableRow> out = parser.attachContinuations(rows);
        out = parser.mergeWrappedNames(out);

        assertThat(out).hasSize(1);
        TableRow only = out.get(0);
        assertThat(only.name).isEqualTo("Continuous Power Dissipation (NC @100C");
        assertThat(only.sym).isEqualTo("Pcn");
        assertThat(only.unit).isEqualTo("W");
        assertThat(only.values).containsEntry("v0", "40.2");
    }

    @Test
    void sectionRowIsNeverSwallowedAsAWrappedName() {
        // 分节名「性能参数」只有名字、无符号/单位/值，绝不能并进相邻参数名
        CatalogParser parser = new CatalogParser(new PdfPageScanner());
        TableRow section = row(100.0, "性能参数", "", "", null);
        section.kind = TableRow.KIND_SECTION;
        List<TableRow> rows = new ArrayList<>(List.of(
                row(95.0, "持续推力", "Fcn", "N", "28.6"),
                section,
                row(105.0, "峰值推力", "Fpk", "N", "99.5")));

        List<TableRow> out = parser.mergeWrappedNames(rows);

        assertThat(out).hasSize(3);
        assertThat(out.get(1).name).isEqualTo("性能参数");
        assertThat(out.get(0).name).isEqualTo("持续推力");
    }

    @Test
    void fallbackColumnLabelsNeverLeakIntoTheModelName() {
        assertThat(CatalogWorkbookWriter.mergeModelConfig("CLA0010-025", "Values"))
                .as("Values 是值列表头，不是型号变体")
                .isEqualTo("CLA0010-025");
        assertThat(CatalogWorkbookWriter.mergeModelConfig("CLA0010-025", "Value"))
                .isEqualTo("CLA0010-025");
        assertThat(CatalogWorkbookWriter.mergeModelConfig("CLA0010-025", "Typical"))
                .isEqualTo("CLA0010-025");
        // 真接法照旧拼上去
        assertThat(CatalogWorkbookWriter.mergeModelConfig("AKM200-A-B8-W", "B"))
                .isEqualTo("AKM200-A-B8-W-B");
    }
}
