package com.motorsizer.app.importing.ocr;

import com.motorsizer.app.importing.render.CatalogParser;
import com.motorsizer.app.importing.render.CatalogTable;
import com.motorsizer.app.importing.render.CatalogWorkbookWriter;
import com.motorsizer.app.importing.render.ParsedCatalog;
import com.motorsizer.app.importing.render.PdfPageScanner;
import com.motorsizer.app.importing.render.TableRow;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * OCR 回退的真图纸回归（客户图纸：文字版图纸，但第 2 页参数表整页贴成位图）。
 *
 * <p>默认 skip —— 真样册不进仓库。指定后运行：
 * <pre>mvn -pl motorsizer-app -am test -DskipFrontend=true \
 *     -Dmotorsizer.test.drawing="...\AKM100-B4-D79 customer drawing.JS.Rev0.20240225.PDF"</pre>
 *
 * <p>断言口径：不比对整表字符串（OCR 结果随模型版本浮动），只锁定「必须识别出来的
 * 锚点」——表头、型号、以及若干含小数/斜杠/度数符号的难认值。这些值一旦回归失败，
 * 说明 OCR 链路（渲染 DPI、坐标换算、CTC 解码、版面列边界）有实质退化。
 */
class OcrCatalogParserTest {

    @Test
    void recognizesImageTableOnCustomerDrawing() throws Exception {
        String pdf = System.getProperty("motorsizer.test.drawing");
        Assumptions.assumeTrue(pdf != null && Files.exists(Path.of(pdf)),
                "未指定 -Dmotorsizer.test.drawing，跳过");

        ParsedCatalog parsed = new CatalogParser(new PdfPageScanner(), new OcrEngine())
                .parse(Path.of(pdf));

        // 1) 第 2 页应被记为「OCR 页」，而不是「图片型参数表页（提不出来）」
        assertThat(parsed.ocrPages()).contains(2);
        assertThat(parsed.imageTablePages()).doesNotContain(2);

        // 2) 应该出表，且只有这张表落在第 2 页
        CatalogTable table = parsed.tables().stream()
                .filter(t -> t.page() == 2)
                .findFirst()
                .orElseThrow(() -> new AssertionError("第 2 页未解析出参数表，OCR 链路退化"));

        // 3) 型号与接法列
        assertThat(table.model()).contains("AKM");
        assertThat(table.configs()).isNotEmpty();

        // 4) 难认值的锚点校验
        Map<String, String> got = new LinkedHashMap<>();
        String kfUnit = "";
        for (TableRow r : table.rows()) {
            if (TableRow.KIND_SECTION.equals(r.kind())) {
                continue;
            }
            if ("Kf".equals(r.sym().trim())) {
                kfUnit = r.unit().trim();
            }
            String v0 = r.values().get("v0");
            if (v0 != null && !v0.isBlank()) {
                got.put(r.sym().trim(), v0.trim());
            }
        }
        assertThat(got)
                .containsEntry("Fcn", "1445")
                .containsEntry("Fpk", "3221")
                .containsEntry("Te", "25.2")
                .containsEntry("Icn", "19.2")
                .containsKey("Km");
        // Kf 的单位是 N/Arms：斜杠最容易在 CTC 解码时被吃掉
        assertThat(kfUnit).isEqualTo("N/Arms");
    }

    /**
     * Excel「识别报告」必须写清「哪几页是 OCR 出来的」——用户拿着 Excel 就能回去只核对那几页，
     * 而不是把整本表都看一遍。
     */
    @Test
    void reportSheetListsOcrPages(@TempDir Path dir) throws Exception {
        String pdf = System.getProperty("motorsizer.test.drawing");
        Assumptions.assumeTrue(pdf != null && Files.exists(Path.of(pdf)),
                "未指定 -Dmotorsizer.test.drawing，跳过");

        ParsedCatalog parsed = new CatalogParser(new PdfPageScanner(), new OcrEngine())
                .parse(Path.of(pdf));
        Assumptions.assumeTrue(!parsed.ocrPages().isEmpty(), "该 PDF 未走 OCR，跳过");

        Path xlsx = dir.resolve("out.xlsx");
        new CatalogWorkbookWriter().write(parsed, xlsx);
        assertThat(xlsx).exists();

        Map<String, String> report = new LinkedHashMap<>();
        DataFormatter fmt = new DataFormatter();
        try (XSSFWorkbook wb = new XSSFWorkbook(Files.newInputStream(xlsx))) {
            Sheet s = wb.getSheet("识别报告");
            assertThat(s).as("识别报告页").isNotNull();
            for (int i = 1; i <= s.getLastRowNum(); i++) {
                Row r = s.getRow(i);
                if (r == null || r.getCell(0) == null) {
                    continue;
                }
                report.put(fmt.formatCellValue(r.getCell(0)).trim(),
                        r.getCell(1) == null ? "" : fmt.formatCellValue(r.getCell(1)).trim());
            }
        }
        assertThat(report).containsKeys("OCR 识别页", "OCR 页说明");
        assertThat(report.get("OCR 识别页")).isEqualTo("2");
        assertThat(report.get("OCR 页说明")).contains("核对");
        assertThat(report.get("PDF 类型")).contains("OCR");
    }

    /**
     * 兜底路径：图纸的文字层里连 SPECIFICATION 标题都没有（标题本身就在图片里），
     * 只能靠「整本一张表都没解析出来 + 页面图片占比高」触发 OCR。
     *
     * <p>用例文件：`ACD85-P-15-Y-K-HF-0.5-NFB-G53REV01.PDF`（3 页，参数表是第 2 页的位图，
     * 图片占比 45.8%，文字层只有标题栏套话）。
     */
    @Test
    void fallsBackToOcrWhenTextLayerHasNoSpecTitle() throws Exception {
        String pdf = System.getProperty("motorsizer.test.drawing2");
        Assumptions.assumeTrue(pdf != null && Files.exists(Path.of(pdf)),
                "未指定 -Dmotorsizer.test.drawing2，跳过");

        ParsedCatalog parsed = new CatalogParser(new PdfPageScanner(), new OcrEngine())
                .parse(Path.of(pdf));

        assertThat(parsed.ocrPages()).as("OCR 页").containsExactly(2);
        assertThat(parsed.tables()).as("出表数").hasSize(1);
        assertThat(parsed.skippedPages()).as("接线图页仍应跳过（OCR 跑了但没表头）").contains(3);

        CatalogTable table = parsed.tables().get(0);
        // 型号行跨列（Model | ACD85-P-15），接法列是 Parallel —— 型号不该把 Parallel 一起吃进去
        assertThat(table.model()).isEqualTo("ACD85-P-15");
        assertThat(table.configs()).contains("Parallel");

        Map<String, String> got = new LinkedHashMap<>();
        String poleUnit = "";
        for (TableRow r : table.rows()) {
            if (TableRow.KIND_SECTION.equals(r.kind())) {
                continue;
            }
            if ("2p".equals(r.sym().trim())) {
                poleUnit = r.unit().trim();
            }
            String v0 = r.values().get("v0");
            if (v0 != null && !v0.isBlank()) {
                got.put(r.sym().trim(), v0.trim());
            }
        }
        assertThat(got)
                .containsEntry("Tcn", "0.54")
                .containsEntry("Tpk", "2.19")
                .containsEntry("Kt", "0.18")
                .containsEntry("R25", "1.92")
                .containsEntry("Icn", "3.8")
                .containsEntry("Kth", "0.715");
        // 「无单位」在样册里写作 -，OCR 会读成汉字「一」，这里验证已归一
        assertThat(poleUnit).isEqualTo("-");
    }
}
