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
        // 2026-11-19 用户裁定：「最高线圈温度」不进采集清单（夹具里刻意放了一行 tmax）
        assertThat(rows).extracting(TableRow::name).doesNotContain("最高线圈温度");
        assertThat(parsed.excludedParams()).containsEntry("最高线圈温度", 1);
        assertThat(parsed.excludedRows()).isEqualTo(1);
        assertThat(rows.get(0).name()).isEqualTo("持续转矩");
        assertThat(rows.get(0).sym()).isEqualTo("Tcn");
        assertThat(rows.get(0).unit()).isEqualTo("Nm");
        assertThat(rows.get(0).values()).containsEntry("v0", "3.4").containsEntry("v1", "3.4");
        assertThat(rows.get(1).values()).containsEntry("v0", "10.0").containsEntry("v1", "10.0");
    }

    /**
     * 小字号数值不能被当成脚注编号吞掉（2026-11-19 真样册踩坑）。
     *
     * <p>AUM3 的参数表用 5pt 排数值，{@code 持续推力 28N}、{@code 电磁周期 60}、
     * {@code 磁吸力 0} 这些「1~2 位纯数字 + 小字号」同时满足旧的脚注判据，
     * 被抽进 footnote 字段、值列变空 —— Excel 里整行没有持续推力，
     * 入库时报「缺少必填参数：持续推力」。
     *
     * <p>判据补上「必须紧贴在前一段正文后面」：数值单元格只有它自己一个 run，永远不会被误判；
     * 真脚注号（名字后面那个 {@code 4}）仍然能被剥出来。
     */
    @Test
    void keepsSmallTwoDigitValuesAsData() throws Exception {
        Path pdf = temp.resolve("small-numbers.pdf");
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.A4);
            doc.addPage(page);
            PDType0Font font = PDType0Font.load(doc, Path.of(CJK_FONT).toFile());
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                text(cs, font, 8, 60, 760, "AUM3-S1");
                text(cs, font, 8, 150, 730, "符号");
                text(cs, font, 8, 200, 730, "单位");
                text(cs, font, 8, 250, 730, "串联");
                text(cs, font, 8, 300, 730, "并联");

                // 带脚注号：名字后面紧跟一个 5pt 的「4」
                String named = "持续推力(水冷)";
                float w = font.getStringWidth(named) / 1000f * 8f;
                text(cs, font, 8, 60, 716, named);
                text(cs, font, 4, 60 + w + 1, 716, "4");
                text(cs, font, 8, 150, 716, "Fcw");
                text(cs, font, 8, 200, 716, "N");
                text(cs, font, 4, 250, 716, "37");      // 4pt 的两位数，是参数值
                text(cs, font, 4, 300, 716, "110");

                // 不带脚注号：两位数 28 也不能被吞
                text(cs, font, 8, 60, 700, "持续推力");
                text(cs, font, 8, 150, 700, "Fcn");
                text(cs, font, 8, 200, 700, "N");
                text(cs, font, 4, 250, 700, "28");
                text(cs, font, 4, 300, 700, "113");

                // 表格至少要几行才会被当成参数表（MIN_NAMED_ROWS）
                String[][] more = {
                        {"峰值推力", "Fpk", "N", "144", "289"},
                        {"力常数", "Kf", "N/Arms", "15.7", "31.4"},
                        {"持续电流", "Icn", "Arms", "1.8", "3.6"}};
                int y = 684;
                for (String[] r : more) {
                    text(cs, font, 8, 60, y, r[0]);
                    text(cs, font, 8, 150, y, r[1]);
                    text(cs, font, 8, 200, y, r[2]);
                    text(cs, font, 4, 250, y, r[3]);
                    text(cs, font, 4, 300, y, r[4]);
                    y -= 14;
                }
            }
            doc.save(pdf.toFile());
        }

        ParsedCatalog parsed = new CatalogParser(new PdfPageScanner()).parse(pdf);
        assertThat(parsed.tables()).hasSize(1);

        List<TableRow> rows = parsed.tables().get(0).rows().stream()
                .filter(r -> !r.isSection()).toList();
        assertThat(rows).hasSize(5);

        TableRow cooled = rows.stream().filter(r -> r.name().startsWith("持续推力(水冷)")).findFirst().orElseThrow();
        // 脚注号仍要剥出来，名字保持干净
        assertThat(cooled.name()).isEqualTo("持续推力(水冷)");
        assertThat(cooled.footnote()).contains("4");
        assertThat(cooled.values()).containsEntry("v0", "37").containsEntry("v1", "110");

        // 小字号两位数必须留在值列
        TableRow plain = rows.stream().filter(r -> r.name().equals("持续推力")).findFirst().orElseThrow();
        assertThat(plain.footnote()).isEmpty();
        assertThat(plain.values()).containsEntry("v0", "28").containsEntry("v1", "113");
    }

    /**
     * 符号的下标不是脚注（2026-11-19 真样册踩坑，ADR050-T-014）。
     *
     * <p>{@code R25} 里的 {@code 25} 是 4pt、紧贴 {@code R}，与真脚注号的特征完全重合；
     * 被抽走后符号只剩 {@code R}，「相间电阻」这一行的 {@code sym} 对不上，整台电机
     * 入库时报「缺少必填参数：相间电阻 @25°C ±10%」（而样册上 3.30 / 0.86 明明都有）。
     *
     * <p>唯一稳定的区别是列：脚注号跟在参数名后面（名字列），下标长在符号里面（符号列）。
     */
    @Test
    void keepsSymbolSubscriptOutOfFootnote() throws Exception {
        Path pdf = temp.resolve("symbol-subscript.pdf");
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.A4);
            doc.addPage(page);
            PDType0Font font = PDType0Font.load(doc, Path.of(CJK_FONT).toFile());
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                text(cs, font, 8, 60, 760, "ADR050-T-014");
                text(cs, font, 8, 150, 730, "符号");
                text(cs, font, 8, 200, 730, "单位");
                text(cs, font, 8, 250, 730, "低速");
                text(cs, font, 8, 300, 730, "高速");

                String name = "相间电阻@25°C ±10%";
                text(cs, font, 8, 60, 716, name);
                text(cs, font, 8, 150, 716, "R");
                text(cs, font, 4, 155, 716, "25");       // 下标，不是脚注
                text(cs, font, 8, 200, 716, "Ω");
                text(cs, font, 8, 250, 716, "3.30");
                text(cs, font, 8, 300, 716, "0.86");

                text(cs, font, 8, 60, 702, "相间电感 ±20%");
                text(cs, font, 8, 150, 702, "L");
                text(cs, font, 8, 200, 702, "mH");
                text(cs, font, 8, 250, 702, "1.25");
                text(cs, font, 8, 300, 702, "0.32");

                text(cs, font, 8, 60, 688, "峰值电流");
                text(cs, font, 8, 150, 688, "Ipk");
                text(cs, font, 8, 200, 688, "Arms");
                text(cs, font, 8, 250, 688, "7.9");
                text(cs, font, 8, 300, 688, "15.8");

                text(cs, font, 8, 60, 674, "转矩常数");
                text(cs, font, 8, 150, 674, "Kt");
                text(cs, font, 8, 200, 674, "Nm/Arms");
                text(cs, font, 8, 250, 674, "0.21");
                text(cs, font, 8, 300, 674, "0.11");

                text(cs, font, 8, 60, 660, "最高转速");
                text(cs, font, 8, 150, 660, "Nmax");
                text(cs, font, 8, 200, 660, "rpm");
                text(cs, font, 8, 250, 660, "1750");
                text(cs, font, 8, 300, 660, "3000");
            }
            doc.save(pdf.toFile());
        }

        ParsedCatalog parsed = new CatalogParser(new PdfPageScanner()).parse(pdf);
        List<TableRow> rows = parsed.tables().get(0).rows().stream()
                .filter(r -> !r.isSection()).toList();

        TableRow r25 = rows.stream().filter(r -> r.name().startsWith("相间电阻")).findFirst().orElseThrow();
        assertThat(r25.sym()).isEqualTo("R25");
        assertThat(r25.unit()).isEqualTo("Ω");
        assertThat(r25.footnote()).doesNotContain("25");
        assertThat(r25.values()).containsEntry("v0", "3.30").containsEntry("v1", "0.86");
    }

    /**
     * 旋转 90° 的图纸标注不能混进参数表（2026-11-19 真样册踩坑，ADR050-T-014 第 269 页）。
     *
     * <p>尺寸图的坐标/电缆标注以 90° 旋转绘制，{@code getXDirAdj()} 在这种文字上退化成
     * 「约等于基线 y」，于是标注被投影到表格中间：单位变 {@code 5Ω}、值变
     * {@code 3.300.86；.0}、「电机常数」行跟着一起坏。按 {@code getDir()} 丢掉旋转字形。
     */
    @Test
    void dropsRotatedAnnotationsFromTable() throws Exception {
        Path pdf = temp.resolve("rotated.pdf");
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.A4);
            doc.addPage(page);
            PDType0Font font = PDType0Font.load(doc, Path.of(CJK_FONT).toFile());
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                text(cs, font, 8, 60, 700, "持续转矩");
                text(cs, font, 8, 150, 700, "Tcn");
                text(cs, font, 8, 200, 700, "Nm");

                // 旋转标注：落在单位列/值列之间
                cs.beginText();
                cs.setFont(font, 6);
                cs.setTextMatrix(org.apache.pdfbox.util.Matrix.getRotateInstance(Math.PI / 2, 210, 690));
                cs.showText("5.07");
                cs.endText();
            }
            doc.save(pdf.toFile());
        }

        try (PDDocument doc = PDDocument.load(pdf.toFile())) {
            PdfPageScanner.PageScan scan = new PdfPageScanner().scan(doc, 1);
            assertThat(scan.runs()).extracting(TextRun::token)
                    .contains("持续转矩", "Tcn", "Nm");
            // 旋转的字整段丢弃，否则它会以「5.07」或碎片形式出现在单位/值列
            assertThat(scan.runs()).noneMatch(r -> r.text().contains("5"));
        }
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

            // 报告页要把「采集口径剔除」写出来：用户看到少一行 tmax 时能对上账
            var report = wb.getSheet("识别报告");
            var fmt = new org.apache.poi.ss.usermodel.DataFormatter();
            Map<String, String> kv = new LinkedHashMap<>();
            for (int i = 1; i <= report.getLastRowNum(); i++) {
                var r = report.getRow(i);
                if (r != null && r.getCell(0) != null) {
                    kv.put(fmt.formatCellValue(r.getCell(0)).trim(),
                            r.getCell(1) == null ? "" : fmt.formatCellValue(r.getCell(1)).trim());
                }
            }
            assertThat(kv).containsKeys("不采集参数", "按采集口径剔除");
            assertThat(kv.get("按采集口径剔除")).contains("最高线圈温度").contains("已剔除");
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
     * 采集口径（2026-11-19 用户裁定）：样册识别导出的采集清单里不需要「最高线圈温度」。
     *
     * <p>三种写法都要拦下：符号 {@code Tmax}、中文名「最高线圈温度」、英文名
     * {@code Max. coil temperature}（客户图纸是英文）；「最高母线电压」「最高转速」
     * 同样带「最高」，不能被误伤。
     */
    @Test
    void dropsCoilTemperatureRows() {
        CatalogParser parser = new CatalogParser(new PdfPageScanner());
        List<TableRow> rows = List.of(
                section("性能参数"),
                param("最高线圈温度", "tmax", "°C", "100"),
                param("最高线圈温度", "Tmax", "°C", "100"),
                param("Max. coil temperature", "TMAX", "°C", "100"),
                param("最高母线电压", "Ubus", "Vdc", "60"),
                param("最高转速", "Nmax", "rpm", "2000"));

        Map<String, Integer> excluded = new LinkedHashMap<>();
        List<TableRow> kept = parser.dropExcludedParameters(rows, excluded);

        assertThat(kept).extracting(TableRow::name)
                .containsExactly("性能参数", "最高母线电压", "最高转速");
        assertThat(excluded)
                .containsEntry("最高线圈温度", 2)
                .containsEntry("Max. coil temperature", 1);
        // 符号列被 OCR 读坏、名字也没取到时，靠符号判断的那一路必须还在
        assertThat(LayoutRules.isExcludedParameter("", "T max")).isTrue();
        assertThat(LayoutRules.isExcludedParameter("最高母线电压", "Ubus")).isFalse();
        assertThat(LayoutRules.isExcludedParameter("最高转速", "Nmax")).isFalse();
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

    /**
     * 宽表（AUM5）的两段式表头：型号名分成两条基线、右边四个型号还带「-V107/-V80」后缀行。
     *
     * <p>旧实现只取「表头上方一行」的型号名，遇到这种版式整行全是后缀（{@code -V107}），
     * 型号分组直接为空 —— 13 列全退化成「串联/串联/并联…」这类裸接法，型号只剩一个 AUM5-S1，
     * S2 之后全部丢失（用户反馈「这几个电机型号没有录入」）。坐标取自真实样册第 41 页。
     */
    @Test
    void groupsModelsAcrossTwoHeaderRowsAndSuffixRow() {
        TextLine modelsRight = new TextLine(85.80, List.of(
                TextRun.of("AUM5-S8", 420.1, 446.5, 85.80, 6.4),
                TextRun.of("AUM5-S9", 449.4, 475.8, 85.80, 6.4),
                TextRun.of("AUM5-S10", 479.8, 509.5, 85.80, 6.4),
                TextRun.of("AUM5-S12", 511.8, 541.5, 85.80, 6.4)));
        TextLine modelsLeft = new TextLine(89.88, List.of(
                TextRun.of("AUM5-S1", 171.4, 199.8, 89.88, 6.6),
                TextRun.of("AUM5-S2", 211.9, 241.9, 89.88, 6.8),
                TextRun.of("AUM5-S3", 264.3, 294.2, 89.88, 6.8),
                TextRun.of("AUM5-S4", 315.0, 344.9, 89.88, 6.8),
                TextRun.of("AUM5-S5", 357.0, 385.5, 89.88, 6.6),
                TextRun.of("AUM5-S6", 388.8, 417.3, 89.88, 6.6)));
        TextLine suffixes = new TextLine(93.96, List.of(
                TextRun.of("-V107", 424.9, 441.7, 93.96, 6.4),
                TextRun.of("-V80", 455.9, 469.4, 93.96, 6.4),
                TextRun.of("-V107", 486.2, 503.1, 93.96, 6.4),
                TextRun.of("-V107", 518.2, 535.1, 93.96, 6.4)));
        TextLine header = new TextLine(103.58, List.of(
                TextRun.of("性能参数", 43.4, 71.3, 103.58, 7),
                TextRun.of("符号", 122.9, 135.0, 103.58, 6),
                TextRun.of("单位", 149.6, 161.7, 103.58, 6),
                TextRun.of("串联", 179.6, 191.6, 103.58, 6),
                TextRun.of("串联", 207.7, 219.8, 103.58, 6),
                TextRun.of("并联", 233.3, 245.3, 103.58, 6),
                TextRun.of("串联", 259.5, 271.6, 103.58, 6),
                TextRun.of("并联", 285.3, 297.3, 103.58, 6),
                TextRun.of("串联", 311.0, 323.1, 103.58, 6),
                TextRun.of("并联", 335.7, 347.8, 103.58, 6),
                TextRun.of("并联", 365.2, 377.3, 103.58, 6),
                TextRun.of("并联", 397.0, 409.1, 103.58, 6),
                TextRun.of("P5", 429.8, 436.8, 103.58, 5),
                TextRun.of("P7", 459.1, 466.1, 103.58, 5),
                TextRun.of("P5", 491.1, 498.1, 103.58, 5),
                TextRun.of("P5", 523.2, 530.2, 103.58, 5)));

        List<TextLine> lines = List.of(modelsRight, modelsLeft, suffixes, header);
        CatalogParser parser = new CatalogParser(new PdfPageScanner());
        CatalogParser.Columns cols = parser.buildColumns(header, 542.9);
        Map<String, List<Integer>> groups = parser.modelGroups(lines, 3, cols);

        assertThat(cols.configTexts()).hasSize(13);
        assertThat(groups).containsOnlyKeys(
                "AUM5-S1", "AUM5-S2", "AUM5-S3", "AUM5-S4", "AUM5-S5", "AUM5-S6",
                "AUM5-S8-V107", "AUM5-S9-V80", "AUM5-S10-V107", "AUM5-S12-V107");
        // 一个型号占 1 列还是 2 列，由型号名与列区间的横向重叠决定
        assertThat(groups.get("AUM5-S1")).containsExactly(3);
        assertThat(groups.get("AUM5-S2")).containsExactly(4, 5);
        assertThat(groups.get("AUM5-S5")).containsExactly(10);
        assertThat(groups.get("AUM5-S8-V107")).containsExactly(12);
        assertThat(groups.get("AUM5-S12-V107")).containsExactly(15);

        assertThat(parser.namesFromTitle(lines, 3, groups)).isEqualTo("AUM5");
        assertThat(parser.columnLabels(cols, groups)).containsExactly(
                "AUM5-S1串联", "AUM5-S2串联", "AUM5-S2并联", "AUM5-S3串联", "AUM5-S3并联",
                "AUM5-S4串联", "AUM5-S4并联", "AUM5-S5并联", "AUM5-S6并联",
                "AUM5-S8-V107-P5", "AUM5-S9-V80-P7", "AUM5-S10-V107-P5", "AUM5-S12-V107-P5");
    }

    /** 型号名里已含绕组代号时不再重复拼：{@code AUM6-P5-S4} + 表头 {@code P5} 还是 {@code AUM6-P5-S4}。 */
    @Test
    void doesNotRepeatWindingCodeAlreadyInModel() {
        TextLine header = new TextLine(90, List.of(
                TextRun.of("符号", 138.3, 150.4, 90, 6),
                TextRun.of("单位", 175.0, 187.1, 90, 6),
                TextRun.of("P5", 210.5, 217.5, 90, 5),
                TextRun.of("P8", 257.1, 264.1, 90, 5)));
        List<TextLine> lines = List.of(
                new TextLine(78.5, List.of(
                        TextRun.of("AUM6-P5-S4", 195.0, 241.0, 78.5, 6.4),
                        TextRun.of("AUM6-P8-S6", 242.0, 288.0, 78.5, 6.4))),
                header);

        CatalogParser parser = new CatalogParser(new PdfPageScanner());
        CatalogParser.Columns cols = parser.buildColumns(header, 300.0);
        Map<String, List<Integer>> groups = parser.modelGroups(lines, 1, cols);

        assertThat(parser.columnLabels(cols, groups))
                .containsExactly("AUM6-P5-S4", "AUM6-P8-S6");
    }

    /**
     * 表右边界不能用「页眉装饰条」的宽度。
     *
     * <p>AUM5 那页的表头行没有色块，分节色块只出现在「机械参数 / 其他信息」两行（右缘 543.2），
     * 而页眉那条装饰色块右缘只有 461.1。旧实现取「最窄色块」，整张表被裁到 461pt，
     * 之后的 S9 / S10 / S12 三列全部丢失。
     */
    @Test
    void tableRightEdgeIgnoresHeaderBand() {
        List<FillBand> bands = List.of(
                new FillBand(28.3, 36.8, -0.9, 461.1),      // 页眉装饰条：横跨标题栏，比表窄
                new FillBand(310.1, 321.8, 41.8, 543.2),    // 「机械参数」分节
                new FillBand(364.3, 375.6, 41.7, 543.2));   // 「其他信息」分节

        assertThat(CatalogParser.tableRightEdge(bands, 103.6, 530.2)).isEqualTo(543.2);
        // 表头行自己有色块时，仍以覆盖表头的那条为准
        List<FillBand> withHeaderBand = List.of(new FillBand(100.0, 110.0, 44.0, 279.0),
                new FillBand(130.0, 140.0, 44.0, 300.0));
        assertThat(CatalogParser.tableRightEdge(withHeaderBand, 103.6, 260.0)).isEqualTo(279.0);
    }

    /**
     * 名字格里的上标脚注号单独落到一条基线上时，不能当成参数名。
     *
     * <p>AUM5 的「热耗散常数(气冷)¹ / (水冷)⁴」两个编号被排在同一条基线上，
     * 整行会被读成参数名「14；热耗散常数(水冷)」，字段字典与冷却变体判定都匹配不上。
     */
    @Test
    void strayFootnoteDigitsInNameColumnAreNotAName() {
        CatalogParser parser = new CatalogParser(new PdfPageScanner());
        CatalogParser.CellText cell = parser.joinCell("name", List.of(
                TextRun.of("1", 109.0, 111.3, 126.2, 4),
                TextRun.of("4", 113.7, 116.0, 126.2, 4)));

        assertThat(cell.text()).isEmpty();
        assertThat(cell.marks()).isEqualTo("14");
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
        // 采集口径（2026-11-19）：性能参数里的「最高线圈温度」不进采集清单，删了几行要能对上账
        assertThat(parsed.tables().stream()
                .flatMap(t -> t.rows().stream())
                .filter(r -> !r.isSection()))
                .noneMatch(r -> r.name().contains("线圈温度"));
        assertThat(parsed.excludedRows()).isPositive();
        Path xlsx = temp.resolve("real.xlsx");
        new CatalogWorkbookWriter().write(parsed, xlsx);
        assertThat(Files.size(xlsx)).isGreaterThan(5000);
    }

    // ---------------------------------------------------------------- 说明页正文

    /**
     * 样册「2. 力和速度的关系」那种说明页：左侧一整段正文，右侧并排一个
     * 「说明 / 单位 / 符号」术语小表，表头词凑得齐全 —— 结果正文长句变成参数名、
     * 术语字母变成值，产出一个假型号。这里用该页的真实文案作夹具。
     */
    @Test
    void prosePageIsNotTreatedAsParameterTable() {
        List<TableRow> rows = List.of(
                proseRow("值对应的作用力的瞬时值。(力N)", "F"),
                proseRow("如果一个物体从静止开始运动，换言之，其初始速(时间s)", "m"),
                proseRow("度为零，然后，给出了速度和加速度之间关系的方(加速度m/s)", "a"),
                proseRow("程：v = at(速度m/s)", "v"),
                proseRow("同样，位移，速度和加速度之间关系的方程：s = ½at2", "s"),
                proseRow("力矩Nm", "W"),
                proseRow("同样，位移，速度和加速度之间关系的方程：τ = ½Jω2", "p"));

        assertThat(CatalogParser.looksLikeProse(rows)).isTrue();
    }

    /** 英文图纸的参数名又长又带逗号，但只要符号列有值就不能当正文丢掉。 */
    @Test
    void longEnglishNamesWithSymbolAreStillATable() {
        List<TableRow> rows = List.of(
                symbolRow("Continuous force (self-cooled) @100°C", "Fcn"),
                symbolRow("Peak force, for 1 second max", "Fpk"),
                symbolRow("Force constant, ±10% tolerance", "Kf"),
                symbolRow("Back EMF constant, ±10% tolerance", "Ke"));

        assertThat(CatalogParser.looksLikeProse(rows)).isFalse();
    }

    /** 中文样册的短参数名照旧通过，哪怕有半数是空符号（参数表里偶尔缺符号）。 */
    @Test
    void shortParameterNamesSurviveEvenWithoutSymbol() {
        List<TableRow> rows = List.of(
                proseRow("持续推力(自冷) @100°C", "3.0"),
                proseRow("峰值推力", "11.9"),
                proseRow("力常数 ±10%", "1.75"),
                proseRow("极距", "21"));

        assertThat(CatalogParser.looksLikeProse(rows)).isFalse();
    }

    private static TableRow proseRow(String name, String value) {
        TableRow r = new TableRow();
        r.name = name;
        r.values.put("v0", value);
        return r;
    }

    private static TableRow symbolRow(String name, String sym) {
        TableRow r = new TableRow();
        r.name = name;
        r.sym = sym;
        r.values.put("v0", "1");
        return r;
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

    private static TableRow param(String name, String sym, String unit, String value) {
        TableRow r = new TableRow();
        r.name = name;
        r.sym = sym;
        r.unit = unit;
        r.values.put("v0", value);
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
                        {"最高线圈温度", "tmax", "°C", "100", "100"},
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
