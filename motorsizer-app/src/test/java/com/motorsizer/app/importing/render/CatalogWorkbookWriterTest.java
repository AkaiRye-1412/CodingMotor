package com.motorsizer.app.importing.render;

import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Excel 写出必须对「数据形状」免疫。
 *
 * <p>这几条用例来自真实失败：客户图纸 `CRM0013` 的参数表只有一列 {@code Value}，
 * 而「原表串联/并联共用一格」的行会触发合并，单列时合并区间只剩一格，POI 直接抛
 * {@code IllegalArgumentException: Merged region D9 must contain 2 or more cells}，
 * 整份 Excel 写不出来、整个任务失败。同类风险还有：型号含 {@code /} 导致工作表名非法、
 * 两列同名导致 {@code Set.copyOf} 抛重复元素。
 *
 * <p>这些都不是「数据错了」，而是「版式不同」——不该让用户丢掉整次转换。
 */
class CatalogWorkbookWriterTest {

    private final CatalogWorkbookWriter writer = new CatalogWorkbookWriter();

    /** 单列 Value 的图纸参数表，带 merged 行：曾经在这里崩。 */
    @Test
    void writesSingleValueColumnTableWithMergedRow(@TempDir Path dir) throws Exception {
        TableRow plain = row("Peak Torque", "Tpk", "Nm", "32.9");
        TableRow merged = row("Continuing value", "", "", "6.90");
        merged.merged = true;                       // 单列下没有「第二格」可合并

        ParsedCatalog data = catalog("CRM0013", List.of("Value"), List.of(plain, merged));

        Path xlsx = dir.resolve("out.xlsx");
        assertThatCode(() -> writer.write(data, xlsx)).doesNotThrowAnyException();
        assertThat(xlsx).exists();

        try (XSSFWorkbook wb = new XSSFWorkbook(Files.newInputStream(xlsx))) {
            assertThat(wb.getSheet("参数汇总")).isNotNull();
            Sheet model = wb.getSheet("CRM0013");
            assertThat(model).as("型号原版式页").isNotNull();
            // 参数名、符号、单位、Value、脚注 五列
            assertThat(model.getRow(1).getLastCellNum()).isEqualTo((short) 5);
        }
    }

    /** 型号带 {@code /}（图纸常见 `AKM100-B4/D79`）：工作表名必须被清洗，不能抛异常。 */
    @Test
    void sanitizesSlashInModelName(@TempDir Path dir) throws Exception {
        ParsedCatalog data = catalog("AKM100-B4/D79", List.of("串联", "并联"),
                List.of(row("Peak Torque", "Tpk", "Nm", "32.9", "34.1")));

        Path xlsx = dir.resolve("out.xlsx");
        assertThatCode(() -> writer.write(data, xlsx)).doesNotThrowAnyException();

        try (XSSFWorkbook wb = new XSSFWorkbook(Files.newInputStream(xlsx))) {
            assertThat(wb.getSheet("AKM100-B4-D79")).as("斜杠应换成 -").isNotNull();
        }
    }

    /** 两列同名（都叫 Value）：{@code Set.copyOf} 会抛重复元素，必须走不到那条路。 */
    @Test
    void toleratesDuplicateColumnLabels(@TempDir Path dir) throws Exception {
        TableRow r = row("Peak Torque", "Tpk", "Nm", "32.9", "34.1");
        ParsedCatalog data = catalog("DUP-1", List.of("Value", "Value"), List.of(r));

        Path xlsx = dir.resolve("out.xlsx");
        assertThatCode(() -> writer.write(data, xlsx)).doesNotThrowAnyException();
    }

    /** 长表「型号」列不该被兜底列名污染：单列 Value 的图型号就是 CRM0013，不是 CRM0013-Value。 */
    @Test
    void fallbackColumnLabelsAreNotGluedIntoModel(@TempDir Path dir) throws Exception {
        ParsedCatalog data = catalog("CRM0013", List.of("Value"),
                List.of(row("Peak Torque", "Tpk", "Nm", "32.9")));

        Path xlsx = dir.resolve("out.xlsx");
        writer.write(data, xlsx);

        DataFormatter fmt = new DataFormatter();
        try (XSSFWorkbook wb = new XSSFWorkbook(Files.newInputStream(xlsx))) {
            Row r = wb.getSheet("参数汇总").getRow(1);
            assertThat(fmt.formatCellValue(r.getCell(0))).isEqualTo("CRM0013");
            assertThat(fmt.formatCellValue(r.getCell(5))).isEqualTo("32.9");
        }
    }

    // ---------------------------------------------------------------- 夹具

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

    private static ParsedCatalog catalog(String model, List<String> labels, List<TableRow> rows) {
        CatalogTable table = new CatalogTable(2, model, labels, labels, rows);
        return new ParsedCatalog("sample.pdf", 3, 2000, true,
                List.of(table), Map.of(), List.of(), List.of(), List.of(2), Map.of());
    }
}
