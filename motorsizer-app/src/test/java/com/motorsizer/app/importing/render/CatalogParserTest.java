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
        // 英文接法同理（客户图纸的配置列写 Parallel / Series）
        assertThat(CatalogWorkbookWriter.compactConfigWord("ACD85-P-15-Parallel"))
                .isEqualTo("ACD85-P-15-P");
        assertThat(CatalogWorkbookWriter.compactConfigWord("ACD85-P-15-Series"))
                .isEqualTo("ACD85-P-15-S");
        // 词组里的 Parallel 不该被缩写
        assertThat(CatalogWorkbookWriter.compactConfigWord("Parallel Shaft"))
                .isEqualTo("Parallel Shaft");
        // 不含接法的型号原样保留
        assertThat(CatalogWorkbookWriter.compactConfigWord("AKH100-N-B2-L")).isEqualTo("AKH100-N-B2-L");
        assertThat(CatalogWorkbookWriter.compactConfigWord("AVM12-6.4")).isEqualTo("AVM12-6.4");
    }

    /**
     * 参数名里的脚注标记要剥出来单独存：留在名字里就永远匹配不上字段字典，
     * Excel 里也会出现 {@code Continuous Power Dissipation (NC) @100C[1]} 这种半截标记。
     */
    @Test
    void stripsFootnoteMarkersFromParameterName() {
        assertThat(CatalogParser.splitNameMarkers("Continuous Torque (NC) @100C[1]"))
                .containsExactly("Continuous Torque (NC) @100C", "1");
        // 中文全角括号、多个编号
        assertThat(CatalogParser.splitNameMarkers("Resistance （2）")).containsExactly("Resistance", "2");
        assertThat(CatalogParser.splitNameMarkers("Inductance [1,3]")).containsExactly("Inductance", "1,3");
        // 括号里是文字描述而不是数字（NC / @25°C）时不能动
        assertThat(CatalogParser.splitNameMarkers("Continuous Torque (NC)"))
                .containsExactly("Continuous Torque (NC)", "");
        // 整格只剩标记：名字在图上、文字层没取到，用占位符顶上
        assertThat(CatalogParser.splitNameMarkers("[1]"))
                .containsExactly(CatalogParser.NAMELESS, "1");
        assertThat(CatalogParser.splitNameMarkers("")).containsExactly("", "");
        // 上标脚注（PDF 里是真上标字符，不是 [3]）
        assertThat(CatalogParser.splitNameMarkers("Inductance ±20%[³]"))
                .containsExactly("Inductance ±20%", "3");
        assertThat(CatalogParser.splitNameMarkers("Max. Speed⁴"))
                .containsExactly("Max. Speed", "4");
        // ℃ 与 °℃ 都还原成 °C，同一列里不该出现三种写法
        assertThat(CatalogParser.splitNameMarkers("Continuous Torque @100℃"))
                .containsExactly("Continuous Torque @100°C", "");
        assertThat(CatalogParser.splitNameMarkers("Continuous Current @100°℃"))
                .containsExactly("Continuous Current @100°C", "");
    }

    /** 图纸的「无单位」写 {@code -}，PDF/OCR 会退化成「一」「■」——统一归成半角横线。 */
    @Test
    void normalizesPlaceholderUnits() {
        assertThat(CatalogParser.normalizeUnit("一")).isEqualTo("-");
        assertThat(CatalogParser.normalizeUnit("■")).isEqualTo("-");
        assertThat(CatalogParser.normalizeUnit("－")).isEqualTo("-");
        // 真单位不能被误伤
        assertThat(CatalogParser.normalizeUnit("Nm")).isEqualTo("Nm");
        assertThat(CatalogParser.normalizeUnit("Ω")).isEqualTo("Ω");
        assertThat(CatalogParser.normalizeUnit("")).isEmpty();
    }

    /** 图纸的表标题常带版次说明：{@code CRM0013-Preliminary} 的型号是 {@code CRM0013}。 */
    @Test
    void dropsDraftWordsFromModelName() {
        assertThat(CatalogParser.cleanModel("CRM0013-Preliminary")).isEqualTo("CRM0013");
        assertThat(CatalogParser.cleanModel("CRM0013-PRELIMINARY")).isEqualTo("CRM0013");
        assertThat(CatalogParser.cleanModel("AXD120-初步")).isEqualTo("AXD120");
        // 不含版次词的型号原样保留
        assertThat(CatalogParser.cleanModel("AKM100-B4-D79")).isEqualTo("AKM100-B4-D79");
        assertThat(CatalogParser.cleanModel("AUM2-S2")).isEqualTo("AUM2-S2");
    }

    /**
     * OCR 会把同一行的「参数名」和「数值」排到两条基线上。还原后就是「有名无值」+
     * 「无名有值」两行；不拼回去，Excel 里就会丢掉这个值（图纸里是 {@code Icn = 6.0}）。
     */
    @Test
    void reunitesNameAndValueSplitAcrossBaselines() {
        TableRow named = new TableRow();
        named.oy = 300;
        named.name = "Continuous Current (NC) @100C";
        named.sym = "Icn";
        named.unit = "Arms";

        TableRow orphan = new TableRow();
        orphan.oy = 292;                            // 同一行，基线差 8pt
        orphan.name = CatalogParser.NAMELESS;
        orphan.values.put("v0", "6.0");

        List<TableRow> out = new CatalogParser(new PdfPageScanner())
                .reuniteBrokenRows(List.of(named, orphan));

        assertThat(out).hasSize(1);
        assertThat(out.get(0).name()).isEqualTo("Continuous Current (NC) @100C");
        assertThat(out.get(0).values()).containsEntry("v0", "6.0");
    }

    /** 拼不上的无名行若只有横线占位，整行没有信息，丢掉而不是留一行「（未识别） —」。 */
    @Test
    void dropsOrphanRowsWhoseValueIsJustADash() {
        TableRow orphan = new TableRow();
        orphan.oy = 500;
        orphan.name = CatalogParser.NAMELESS;
        orphan.values.put("v0", "—");

        assertThat(new CatalogParser(new PdfPageScanner()).reuniteBrokenRows(List.of(orphan)))
                .isEmpty();
    }

    /**
     * 客户图纸：标题写着 SPECIFICATION，但整张参数表是贴进来的位图。
     *
     * <p>此时不能用「未识别到表头」敷衍过去——要能说清是「参数表本身是图片」，
     * 用户才知道该换文字版样册、或按图手工录入。
     */
    @Test
    void flagsSpecPageWhoseTableIsAnImage() throws Exception {
        Path pdf = temp.resolve("drawing.pdf");
        writeImageOnlySpecPdf(pdf);

        ParsedCatalog parsed = new CatalogParser(new PdfPageScanner()).parse(pdf);

        assertThat(parsed.tables()).isEmpty();
        assertThat(parsed.skippedPages()).containsExactly(1);
        assertThat(parsed.imageTablePages()).containsExactly(1);
    }

    /** 同样的整页大图，但没有规格标题：属普通图纸页，不该提示「参数表是图片」。 */
    @Test
    void plainDrawingPageIsNotReportedAsImageTable() throws Exception {
        Path pdf = temp.resolve("plain-drawing.pdf");
        writeImageOnlySpecPdf(pdf, "OUTLINE DIMENSIONS");

        ParsedCatalog parsed = new CatalogParser(new PdfPageScanner()).parse(pdf);

        assertThat(parsed.skippedPages()).containsExactly(1);
        assertThat(parsed.imageTablePages()).isEmpty();
    }

    private void writeImageOnlySpecPdf(Path target) throws IOException {
        writeImageOnlySpecPdf(target, "SPECIFICATION:");
    }

    /**
     * 造一页「文字标题 + 整页位图」的图纸：位图占页面约 60%，文字层只有标题，
     * 因此必然解析不出参数表——正是客户图纸最常见的样子。
     */
    private void writeImageOnlySpecPdf(Path target, String heading) throws IOException {
        java.awt.image.BufferedImage bi =
                new java.awt.image.BufferedImage(400, 400, java.awt.image.BufferedImage.TYPE_INT_RGB);
        java.awt.Graphics2D g = bi.createGraphics();
        g.setColor(java.awt.Color.WHITE);
        g.fillRect(0, 0, 400, 400);
        g.setColor(java.awt.Color.BLACK);
        g.drawString("Model  Symbol  Unit  Value", 20, 40);
        g.dispose();
        java.io.ByteArrayOutputStream png = new java.io.ByteArrayOutputStream();
        javax.imageio.ImageIO.write(bi, "png", png);

        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.A4);
            doc.addPage(page);
            PDType0Font font = PDType0Font.load(doc, Path.of(CJK_FONT).toFile());
            org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject img =
                    org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject
                            .createFromByteArray(doc, png.toByteArray(), "table");
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                text(cs, font, 10, 50, 780, heading);
                cs.drawImage(img, 50, 100, 500, 600);      // 30 万 pt² / A4 50.1 万 ≈ 60%
            }
            doc.save(target.toFile());
        }
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
        // 06 v2 起接法并入型号（AUM1-S1 + 串联 → AUM1-S1串联），标签不再等于裸的「串联」，
        // 因此按「整本里既出现过串联列又出现过并联列」来断言。
        assertThat(parsed.tables().stream().flatMap(t -> t.labels().stream()))
                .anyMatch(l -> l.contains("串联"))
                .anyMatch(l -> l.contains("并联"));
        for (CatalogTable t : parsed.tables()) {
            assertThat(t.labels()).isNotEmpty();
            assertThat(t.model()).matches("[A-Z].*");
            // 只保留性能参数：机械参数 / 其他信息 / 编码器参数的分节行不应出现在结果里
            assertThat(t.rows().stream().filter(TableRow::isSection).map(TableRow::name))
                    .allMatch(LayoutRules::isKeptSection);
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
