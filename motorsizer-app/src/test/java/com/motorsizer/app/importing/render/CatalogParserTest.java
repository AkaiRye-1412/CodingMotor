package com.motorsizer.app.importing.render;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 版面解析回归（06 v2 §5）。
 *
 * <p>两段验证：
 * <ol>
 *   <li>{@link #parsesSingleConfigTable()} —— 用 PDFBox 现造一页「符号 + 单位 + 串联/并联」的样册式表格，
 *       校验表头定位、列边界、型号识别与取值；不依赖外部文件，任何机器都能跑。</li>
 *   <li>{@link #parsesRealSample()} —— 传入 {@code -Dmotorsizer.test.pdf=<样册.pdf>} 时对真实样册做整体回归，
 *       未指定则跳过。</li>
 * </ol>
 */
class CatalogParserTest {

    private static final String CJK_FONT = "C:/Windows/Fonts/simhei.ttf";

    @TempDir
    Path temp;

    @Test
    void parsesSingleConfigTable() throws Exception {
        Path pdf = temp.resolve("sample.pdf");
        writeSamplePdf(pdf);

        CatalogParser parser = new CatalogParser(new PdfPageScanner());
        ParsedCatalog parsed = parser.parse(pdf);

        assertThat(parsed.pageCount()).isEqualTo(1);
        assertThat(parsed.hasTextLayer()).isTrue();
        assertThat(parsed.tables()).hasSize(1);

        CatalogTable table = parsed.tables().get(0);
        assertThat(table.model()).isEqualTo("AXD120-71");
        assertThat(table.labels()).containsExactly("串联", "并联");

        List<TableRow> rows = table.rows().stream().filter(r -> !r.isSection()).toList();
        assertThat(rows).hasSize(4);
        assertThat(rows.get(0).name()).isEqualTo("持续转矩");
        assertThat(rows.get(0).sym()).isEqualTo("Tcn");
        assertThat(rows.get(0).unit()).isEqualTo("Nm");
        assertThat(rows.get(0).values()).containsEntry("v0", "3.4").containsEntry("v1", "3.4");
        assertThat(rows.get(1).values()).containsEntry("v0", "10.0").containsEntry("v1", "10.0");
    }

    @Test
    void writesWorkbookWithFourSheets() throws Exception {
        Path pdf = temp.resolve("sample.pdf");
        writeSamplePdf(pdf);
        ParsedCatalog parsed = new CatalogParser(new PdfPageScanner()).parse(pdf);

        Path xlsx = temp.resolve("out.xlsx");
        CatalogWorkbookWriter.Result result = new CatalogWorkbookWriter().write(parsed, xlsx);

        assertThat(Files.size(xlsx)).isGreaterThan(1000);
        assertThat(result.dataRows()).isEqualTo(8);       // 4 个参数 × 串联/并联各一行
        assertThat(result.wide()).isTrue();

        try (var wb = new org.apache.poi.xssf.usermodel.XSSFWorkbook(Files.newInputStream(xlsx))) {
            assertThat(wb.getSheet("参数汇总")).isNotNull();
            assertThat(wb.getSheet("AXD120-71")).isNotNull();
            assertThat(wb.getSheet("识别报告")).isNotNull();
            var summary = wb.getSheet("参数汇总");
            // 统一 6 列：接法压进型号，只留采集要用的字段（脚注/页不采集）
            assertThat(summary.getRow(0).getPhysicalNumberOfCells()).isEqualTo(6);
            assertThat(summary.getRow(0).getCell(5).getStringCellValue()).isEqualTo("值");
            assertThat(summary.getRow(1).getCell(0).getStringCellValue()).isEqualTo("AXD120-71-S");
            assertThat(summary.getRow(1).getCell(2).getStringCellValue()).isEqualTo("持续转矩");
            assertThat(summary.getRow(1).getCell(5).getNumericCellValue()).isEqualTo(3.4);
            assertThat(summary.getRow(2).getCell(0).getStringCellValue()).isEqualTo("AXD120-71-P");
            assertThat(summary.getRow(2).getCell(5).getNumericCellValue()).isEqualTo(3.4);
        }
    }

    @Test
    void keepsOnlyPerformanceSection() {
        CatalogParser parser = new CatalogParser(new PdfPageScanner());
        List<TableRow> rows = List.of(
                section("性能参数"), dataRow("持续转矩"), dataRow("峰值转矩"),
                section("机械参数"), dataRow("转子惯量"),
                section("其他信息"), dataRow("绝缘等级"),
                section("编码器参数"), dataRow("编码器分辨率"));

        Map<String, Integer> counts = new LinkedHashMap<>();
        List<TableRow> kept = parser.keepWantedSections(rows, counts);

        assertThat(kept).extracting(TableRow::name)
                .containsExactly("性能参数", "持续转矩", "峰值转矩");
        assertThat(counts)
                .containsEntry("性能参数", 2)
                .containsEntry("机械参数", 1)
                .containsEntry("其他信息", 1)
                .containsEntry("编码器参数", 1);
    }

    /**
     * 接法列不能只认「串联/并联」这类词：AKH 系列用 L/H 标列，
     * 认不出就会退化成整表一列「值」，数值还被并进单位列。
     */
    @Test
    void lettersRightOfUnitBecomeConfigColumns() {
        TextLine header = new TextLine(92.1, List.of(
                TextRun.of("性能参数", 42.5, 70.3, 92.1, 7),
                TextRun.of("符号", 152.7, 164.8, 92.1, 6),
                TextRun.of("单位", 215.9, 228.0, 92.1, 6),
                TextRun.of("L", 270.5, 273.9, 92.1, 6),
                TextRun.of("H", 301.7, 306.2, 92.1, 6)));

        CatalogParser.Columns cols = new CatalogParser(new PdfPageScanner()).buildColumns(header, 447.5);

        assertThat(cols.configTexts()).containsExactly("L", "H");
        assertThat(cols.at(221.0)).isEqualTo("unit");     // 单位列
        assertThat(cols.at(269.3)).isEqualTo("v0");       // 第一列数值，不能被单位列吃掉
        assertThat(cols.at(301.1)).isEqualTo("v1");
    }

    @Test
    void mergesModelAndConfigIntoOneColumn() {
        // 配置名已含型号：重复部分只留一份（用户裁定：AUM1 + AUM1-S1串联 → AUM1-S1-S）
        assertThat(CatalogWorkbookWriter.mergeModelConfig("AUM1", "AUM1-S1串联"))
                .isEqualTo("AUM1-S1-S");
        assertThat(CatalogWorkbookWriter.mergeModelConfig("AKH100-N-B2", "AKH100-N-B2-L"))
                .isEqualTo("AKH100-N-B2-L");
        // 完全相等只留一个
        assertThat(CatalogWorkbookWriter.mergeModelConfig("AUM1", "AUM1")).isEqualTo("AUM1");
        // 配置名与型号无关：中文直接粘、ASCII 用 - 隔开
        assertThat(CatalogWorkbookWriter.mergeModelConfig("AUM1", "双编码器"))
                .isEqualTo("AUM1双编码器");
        assertThat(CatalogWorkbookWriter.mergeModelConfig("AKH100-N", "B2-L"))
                .isEqualTo("AKH100-N-B2-L");
        // 空值兜底
        assertThat(CatalogWorkbookWriter.mergeModelConfig("AUM1", "")).isEqualTo("AUM1");
        assertThat(CatalogWorkbookWriter.mergeModelConfig("", "AUM1-S1串联")).isEqualTo("AUM1-S1-S");
        // 兜底列名（无接法信息）不拼进型号
        assertThat(CatalogWorkbookWriter.mergeModelConfig("AXD120-71", "值")).isEqualTo("AXD120-71");
        assertThat(CatalogWorkbookWriter.mergeModelConfig("AXD120-71", "v1")).isEqualTo("AXD120-71");
    }

    @Test
    void compactsChineseConfigSuffix() {
        // 用户裁定：串联 → -S、并联 → -P
        assertThat(CatalogWorkbookWriter.compactConfigWord("AUM2-S2串联")).isEqualTo("AUM2-S2-S");
        assertThat(CatalogWorkbookWriter.compactConfigWord("AUM2-S2并联")).isEqualTo("AUM2-S2-P");
        assertThat(CatalogWorkbookWriter.compactConfigWord("AKM200-W-B4并联"))
                .isEqualTo("AKM200-W-B4-P");
        // 已有连字符时不叠成 --
        assertThat(CatalogWorkbookWriter.compactConfigWord("AUM2-S2-串联")).isEqualTo("AUM2-S2-S");
        // 不含接法的型号原样保留
        assertThat(CatalogWorkbookWriter.compactConfigWord("AKH100-N-B2-L")).isEqualTo("AKH100-N-B2-L");
        assertThat(CatalogWorkbookWriter.compactConfigWord("AVM12-6.4")).isEqualTo("AVM12-6.4");
    }

    @Test
    void parsesRealSample() throws Exception {
        String sample = System.getProperty("motorsizer.test.pdf");
        Assumptions.assumeTrue(sample != null && Files.exists(Path.of(sample)),
                "未指定 -Dmotorsizer.test.pdf，跳过真实样册回归");

        ParsedCatalog parsed = new CatalogParser(new PdfPageScanner()).parse(Path.of(sample));

        assertThat(parsed.tables()).isNotEmpty();
        assertThat(parsed.modelCount()).isPositive();
        assertThat(parsed.parameterRows()).isGreaterThan(50);
        assertThat(parsed.footnotes()).isNotEmpty();
        for (CatalogTable t : parsed.tables()) {
            assertThat(t.labels()).contains("串联").contains("并联");
            assertThat(t.model()).matches("[A-Z].*");
            // 只保留性能参数：机械参数 / 其他信息 / 编码器参数的分节行不应出现在结果里
            assertThat(t.rows().stream().filter(TableRow::isSection).map(TableRow::name))
                    .allMatch(LayoutRules.KEEP_SECTIONS::contains);
        }
        assertThat(parsed.droppedRows()).isPositive();
        Path xlsx = temp.resolve("real.xlsx");
        new CatalogWorkbookWriter().write(parsed, xlsx);
        assertThat(Files.size(xlsx)).isGreaterThan(5000);
    }

    // ---------------------------------------------------------------- 夹具

    private static TableRow section(String name) {
        TableRow r = new TableRow();
        r.name = name;
        r.kind = TableRow.KIND_SECTION;
        r.section = name;
        return r;
    }

    private static TableRow dataRow(String name) {
        TableRow r = new TableRow();
        r.name = name;
        r.values.put("v0", "1");
        return r;
    }

    /**
     * 造一页样册式表格：型号行 → 表头行（符号/单位/串联/并联）→ 4 行参数。
     * 坐标是刻意的：每列间距足够大，保证列边界推导稳定。
     */
    private void writeSamplePdf(Path target) throws IOException {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.A4);
            doc.addPage(page);
            PDType0Font font = PDType0Font.load(doc, Path.of(CJK_FONT).toFile());
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                text(cs, font, 8, 60, 760, "AXD120-71");
                text(cs, font, 8, 150, 730, "符号");
                text(cs, font, 8, 200, 730, "单位");
                text(cs, font, 8, 250, 730, "串联");
                text(cs, font, 8, 300, 730, "并联");

                String[][] rows = {
                        {"持续转矩", "Tcn", "Nm", "3.4", "3.4"},
                        {"峰值转矩", "Tpk", "Nm", "10.0", "10.0"},
                        {"转矩常数", "Kt", "Nm/Arms", "3.0", "1.5"},
                        {"最高转速", "Nmax", "rpm", "1700", "2000"}};
                int y = 716;
                for (String[] row : rows) {
                    text(cs, font, 8, 60, y, row[0]);
                    text(cs, font, 8, 150, y, row[1]);
                    text(cs, font, 8, 200, y, row[2]);
                    text(cs, font, 8, 250, y, row[3]);
                    text(cs, font, 8, 300, y, row[4]);
                    y -= 14;
                }
            }
            doc.save(target.toFile());
        }
    }

    private void text(PDPageContentStream cs, PDType0Font font, float size,
                      float x, float y, String value) throws IOException {
        cs.beginText();
        cs.setFont(font, size);
        cs.newLineAtOffset(x, y);
        cs.showText(value);
        cs.endText();
    }
}
