package com.motorsizer.app.importing.render;

import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.HorizontalAlignment;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.VerticalAlignment;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFCellStyle;
import org.apache.poi.xssf.usermodel.XSSFColor;
import org.apache.poi.xssf.usermodel.XSSFFont;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 解析结果 → Excel（pdf2excel.py 的 Excel 输出部分）。
 *
 * <p>版式对齐脚本产物：{@code 参数汇总}（长表/宽表）＋ 型号不多时每型号一页原版式 ＋
 * {@code 脚注说明} ＋ {@code 识别报告}。
 */
@Component
public class CatalogWorkbookWriter {

    private static final String FONT = "微软雅黑";
    private static final byte[] HEAD_FILL = {0x1F, 0x38, 0x64};
    private static final byte[] SECTION_FILL = {(byte) 0xD9, (byte) 0xE2, (byte) 0xF3};
    private static final byte[] BORDER_RGB = {(byte) 0xBF, (byte) 0xBF, (byte) 0xBF};
    private static final Pattern NUMERIC = Pattern.compile("^-?\\d+(?:\\.\\d+)?(?:[Ee][+-]?\\d+)?$");

    /** 型号值里的中文接法后缀：{@code 串联 → -S}、{@code 并联 → -P}（前面若已有连字符不再叠加）。 */
    private static final Pattern CONFIG_SUFFIX = Pattern.compile("-?(?:串联|并联)");

    /** 兜底列名（{@code 值 / 列3 / v1}）：拼型号时丢弃，避免出现「AXD120-71值」。 */
    private static final Pattern FALLBACK_LABEL = Pattern.compile("(?:v|列)\\d+");
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** 写出结果（统计列进 job.json，供前端展示）。 */
    public record Result(int dataRows, boolean wide) {
    }

    public Result write(ParsedCatalog data, Path target) throws IOException {
        if (target.getParent() != null) {
            Files.createDirectories(target.getParent());
        }
        try (XSSFWorkbook wb = new XSSFWorkbook();
             OutputStream out = Files.newOutputStream(target)) {
            StyleBank styles = new StyleBank(wb);
            Result result = writeSummarySheet(wb, styles, data);
            writeModelSheets(wb, styles, data);
            writeFootnoteSheet(wb, styles, data);
            writeReportSheet(wb, styles, data, result);

            Path src = Path.of(data.source());
            String stem = src.getFileName() == null ? "样册" : src.getFileName().toString();
            int dot = stem.lastIndexOf('.');
            if (dot > 0) {
                stem = stem.substring(0, dot);
            }
            wb.getProperties().getCoreProperties().setTitle(stem + " 电机参数表");
            wb.getProperties().getCoreProperties().setCreator("MotorSizer（AI 辅助生成）");
            wb.getProperties().getCoreProperties().setDescription(
                    "由 PDF 文字层坐标级解析生成；AI 生成内容，使用前请与原样册核对。");
            wb.write(out);
            return result;
        }
    }

    // ---------------------------------------------------------------- 参数汇总

    private Result writeSummarySheet(XSSFWorkbook wb, StyleBank styles, ParsedCatalog data) {
        // 源表是不是「串联 | 并联」两列版式：只用于报告标注，输出结构一律是单列「值」。
        boolean wide = data.tables().stream().allMatch(t ->
                t.labels().size() <= 2 && Set.copyOf(t.labels()).stream()
                        .allMatch(l -> l.equals("串联") || l.equals("并联")));

        Sheet ws = wb.createSheet("参数汇总");
        List<String> heads = List.of("型号", "类别", "参数名", "符号", "单位", "值");
        styles.head(ws, 0, heads);

        int r = 1;
        for (CatalogTable table : data.tables()) {
            String section = "";
            for (TableRow row : table.rows()) {
                if (row.isSection()) {
                    section = row.name;
                    continue;
                }
                if (row.values.isEmpty()) {
                    continue;
                }
                for (Map.Entry<String, String> e : outputCells(table, row).entrySet()) {
                    // 型号列 = 型号 + 该接法（串联 → -S、并联 → -P），去重后拼成完整型号
                    set(ws, r, 0, mergeModelConfig(table.model(), e.getKey()), styles, true);
                    set(ws, r, 1, section, styles, true);
                    set(ws, r, 2, row.name, styles, false);
                    set(ws, r, 3, row.sym, styles, true);
                    set(ws, r, 4, row.unit, styles, true);
                    set(ws, r, 5, e.getValue(), styles, true);
                    r++;
                }
            }
        }

        int[] widths = {24, 11, 30, 10, 12, 13};
        for (int i = 0; i < widths.length; i++) {
            ws.setColumnWidth(i, widths[i] * 256);
        }
        ws.createFreezePane(3, 1);
        if (r > 1) {
            ws.setAutoFilter(new CellRangeAddress(0, r - 1, 0, 5));
        }
        return new Result(r - 1, wide);
    }

    /**
     * 一行参数展开成「接法 → 值」。空值不出行；原表里串联/并联共用一格（合并单元格）的行，
     * 两个接法各出一行同值，避免下游只拿到串联、丢掉并联。
     */
    private Map<String, String> outputCells(CatalogTable table, TableRow row) {
        Map<String, String> cells = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : sortedValues(row)) {
            String value = e.getValue();
            if (value == null || value.isBlank()) {
                continue;
            }
            int idx = TableRow.indexOf(e.getKey());
            String label = idx < table.labels().size() ? table.labels().get(idx) : e.getKey();
            cells.put(label == null || label.isBlank() ? "值" : label, value);
        }
        if (row.merged && cells.size() == 1 && isSeriesPair(table.labels())) {
            String only = cells.values().iterator().next();
            cells = new LinkedHashMap<>();
            cells.put("串联", only);
            cells.put("并联", only);
        }
        return cells;
    }

    private static boolean isSeriesPair(List<String> labels) {
        return labels.size() == 2 && labels.contains("串联") && labels.contains("并联");
    }

    /**
     * 长表的「型号」列 = 型号 + 配置，两者重复的部分只留一份；
     * 末尾的中文接法再压成短后缀（{@code 串联 → -S}、{@code 并联 → -P}）。
     * <p>样册里配置名通常已含型号前缀：{@code AUM1} + {@code AUM1-S1串联} → {@code AUM1-S1-S}；
     * 若配置名与型号无关（{@code AUM1} + {@code 双编码器}），按中文不加分隔、ASCII 加 {@code -} 拼接。
     */
    static String mergeModelConfig(String model, String config) {
        String m = model == null ? "" : model.strip();
        String c = config == null ? "" : config.strip();
        if (c.equals("值") || FALLBACK_LABEL.matcher(c).matches()) {
            c = "";                                // 「值 / 列N / vN」是兜底列名，本身没有型号含义
        }
        String merged;
        if (c.isEmpty() || c.equals(m)) {
            merged = c.isEmpty() ? m : c;
        } else if (m.isEmpty()) {
            merged = c;
        } else if (c.startsWith(m)) {
            merged = c;                            // 完全重复的前缀去重：只保留配置里那份
        } else if (m.endsWith(c)) {
            merged = m;                            // 反向重复（极少见）
        } else {
            boolean glue = isAscii(m.charAt(m.length() - 1)) && isAscii(c.charAt(0));
            merged = m + (glue ? "-" : "") + c;
        }
        return compactConfigWord(merged);
    }

    /**
     * 把型号值里的中文接法压成后缀：{@code AUM2-S2串联 → AUM2-S2-S}、{@code AUM2-S2并联 → AUM2-S2-P}。
     * <p>已经是 {@code -串联} 的不会再叠一个连字符。
     */
    static String compactConfigWord(String value) {
        if (value == null || value.isEmpty()
                || (value.indexOf('串') < 0 && value.indexOf('并') < 0)) {
            return value == null ? "" : value;
        }
        StringBuilder sb = new StringBuilder(value.length());
        Matcher matcher = CONFIG_SUFFIX.matcher(value);
        while (matcher.find()) {
            String text = matcher.group();
            String suffix = text.contains("串") ? "-S" : "-P";
            matcher.appendReplacement(sb, suffix);
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    private static boolean isAscii(char ch) {
        return ch < 128 && Character.isLetterOrDigit(ch);
    }

    private List<Map.Entry<String, String>> sortedValues(TableRow row) {
        return row.values.entrySet().stream()
                .sorted((a, b) -> Integer.compare(TableRow.indexOf(a.getKey()), TableRow.indexOf(b.getKey())))
                .toList();
    }

    // ---------------------------------------------------------------- 型号原版式页

    private void writeModelSheets(XSSFWorkbook wb, StyleBank styles, ParsedCatalog data) {
        List<String> models = CatalogParser.distinctModels(data.tables());
        if (models.size() > 12) {
            return;                                   // 型号过多时只出长表，避免上百个可分页
        }
        for (CatalogTable table : data.tables()) {
            String name = table.model().length() > 28 ? table.model().substring(0, 28) : table.model();
            if (name.isBlank()) {
                name = "型号";
            }
            if (wb.getSheet(name) != null) {
                name = name + "_" + table.page();
            }
            int suffix = 2;
            while (wb.getSheet(name) != null) {
                name = (table.model().length() > 26 ? table.model().substring(0, 26) : table.model())
                        + "_" + table.page() + "_" + suffix++;
            }

            Sheet s = wb.createSheet(name);
            Row title = s.createRow(0);
            Cell titleCell = title.createCell(0);
            titleCell.setCellValue(table.model() + " 参数表（源：第 " + table.page() + " 页）");
            titleCell.setCellStyle(styles.title());

            List<String> hd = new java.util.ArrayList<>(List.of("参数名", "符号", "单位"));
            hd.addAll(table.labels());
            hd.add("脚注");
            styles.head(s, 1, hd);

            int rr = 2;
            for (TableRow row : table.rows()) {
                if (row.isSection()) {
                    Row sr = s.createRow(rr);
                    for (int c = 0; c < hd.size(); c++) {
                        Cell cell = sr.createCell(c);
                        cell.setCellStyle(styles.section());
                    }
                    Cell label = sr.getCell(0);
                    label.setCellValue(row.name);
                    s.addMergedRegion(new CellRangeAddress(rr, rr, 0, hd.size() - 1));
                    rr++;
                    continue;
                }
                Row dr = s.createRow(rr);
                set(s, rr, 0, row.name, styles, false);
                set(s, rr, 1, row.sym, styles, true);
                set(s, rr, 2, row.unit, styles, true);
                for (int i = 0; i < table.labels().size(); i++) {
                    set(s, rr, 3 + i, row.values.get("v" + i), styles, true);
                }
                if (!row.footnote.isEmpty()) {
                    set(s, rr, 3 + table.labels().size(), row.footnote, styles, true);
                } else {
                    styles.apply(s, rr, 3 + table.labels().size(), true, null);
                }
                if (row.merged) {
                    s.addMergedRegion(new CellRangeAddress(rr, rr, 3, 2 + table.labels().size()));
                }
                rr++;
            }

            s.setColumnWidth(0, 30 * 256);
            s.setColumnWidth(1, 10 * 256);
            s.setColumnWidth(2, 12 * 256);
            for (int i = 0; i < table.labels().size(); i++) {
                s.setColumnWidth(3 + i, 13 * 256);
            }
            s.setColumnWidth(3 + table.labels().size(), 6 * 256);
            s.createFreezePane(0, 2);
            if (rr > 2) {
                s.setAutoFilter(new CellRangeAddress(1, rr - 1, 0, hd.size() - 1));
            }
        }
    }

    // ---------------------------------------------------------------- 脚注

    private void writeFootnoteSheet(XSSFWorkbook wb, StyleBank styles, ParsedCatalog data) {
        if (data.footnotes().isEmpty()) {
            return;
        }
        Sheet s = wb.createSheet("脚注说明");
        styles.head(s, 0, List.of("编号", "说明"));
        int i = 1;
        for (Map.Entry<Integer, String> e : data.footnotes().entrySet().stream()
                .sorted(Map.Entry.comparingByKey()).toList()) {
            Row row = s.createRow(i);
            Cell no = row.createCell(0);
            no.setCellValue(e.getKey());
            no.setCellStyle(styles.body(true, "0"));
            Cell textCell = row.createCell(1);
            textCell.setCellValue(e.getValue());
            textCell.setCellStyle(styles.wrap());
            row.setHeightInPoints(32f);
            i++;
        }
        s.setColumnWidth(0, 7 * 256);
        s.setColumnWidth(1, 88 * 256);
    }

    // ---------------------------------------------------------------- 识别报告

    private void writeReportSheet(XSSFWorkbook wb, StyleBank styles, ParsedCatalog data, Result result) {
        Sheet s = wb.createSheet("识别报告");
        Path src = Path.of(data.source());
        List<String[]> info = List.of(
                new String[]{"项目", "内容"},
                new String[]{"源文件", String.valueOf(src.getFileName())},
                new String[]{"PDF 类型", data.hasTextLayer() ? "文字版（含文字层）" : "扫描件（无文字层）"},
                new String[]{"总页数", String.valueOf(data.pageCount())},
                new String[]{"识别出参数表的页", String.valueOf(data.parsedPageCount())},
                new String[]{"参数表数量", String.valueOf(data.tables().size())},
                new String[]{"型号数", String.valueOf(data.modelCount())},
                new String[]{"参数行数（保留后）", String.valueOf(data.parameterRows())},
                new String[]{"保留分节", String.join("、", LayoutRules.KEEP_SECTIONS)},
                new String[]{"分节明细", sectionSummary(data)},
                new String[]{"丢弃行数", String.valueOf(data.droppedRows())},
                new String[]{"长表数据行", String.valueOf(result.dataRows())},
                new String[]{"脚注条数", String.valueOf(data.footnotes().size())},
                new String[]{"未识别页", data.skippedPages().isEmpty() ? "无"
                        : String.join("、", data.skippedPages().stream().limit(80).map(String::valueOf).toList())},
                new String[]{"识别方式", "坐标级版面解析；表头中心线定列、底色块定分节；无 OCR / 视觉模型"},
                new String[]{"数值精度", "按原字符串小数位设置格式，10.0 / 0.50 / 1.18E-03 原样显示"},
                new String[]{"生成时间", LocalDateTime.now().format(STAMP)},
                new String[]{"AI 标识", "AI 辅助生成（MotorSizer），使用前请与原样册核对"});

        styles.head(s, 0, List.of("项目", "内容"));
        for (int i = 1; i < info.size(); i++) {
            Row row = s.createRow(i);
            Cell k = row.createCell(0);
            k.setCellValue(info.get(i)[0]);
            k.setCellStyle(styles.reportKey());
            Cell v = row.createCell(1);
            String value = info.get(i)[1];
            if (NUMERIC.matcher(value).matches()) {
                v.setCellValue(Double.parseDouble(value));
            } else {
                v.setCellValue(value);
            }
            v.setCellStyle(styles.reportValue());
        }
        s.setColumnWidth(0, 18 * 256);
        s.setColumnWidth(1, 88 * 256);
    }

    /** 「识别报告」里的分节说明：每节命中多少行、保留还是丢弃，让过滤结果可追溯。 */
    private String sectionSummary(ParsedCatalog data) {
        if (data.sectionRows().isEmpty()) {
            return "未识别到分节标记（表内无底色块、表头左侧也无分节名），因此未做分节过滤";
        }
        List<String> parts = new ArrayList<>();
        for (Map.Entry<String, Integer> e : data.sectionRows().entrySet()) {
            String name = e.getKey().isEmpty() ? "（表头区，无分节）" : e.getKey();
            boolean kept = e.getKey().isEmpty() || LayoutRules.KEEP_SECTIONS.contains(e.getKey());
            parts.add(name + " " + e.getValue() + " 行" + (kept ? "（保留）" : "（已丢弃）"));
        }
        return String.join("｜", parts);
    }

    // ---------------------------------------------------------------- 单元格写入

    private void set(Sheet sheet, int rowIdx, int colIdx, String raw, StyleBank styles, boolean center) {
        Row row = sheet.getRow(rowIdx);
        if (row == null) {
            row = sheet.createRow(rowIdx);
        }
        Cell cell = row.getCell(colIdx);
        if (cell == null) {
            cell = row.createCell(colIdx);
        }
        String text = raw == null ? "" : raw.strip();
        if (text.isEmpty()) {
            styles.apply(sheet, rowIdx, colIdx, center, null);
            return;
        }
        if (NUMERIC.matcher(text).matches()) {
            boolean isInteger = text.indexOf('.') < 0 && !text.toUpperCase().contains("E");
            String digits = text.startsWith("-") ? text.substring(1) : text;
            if (isInteger && (digits.length() > 15
                    || (digits.length() > 1 && digits.startsWith("0")))) {
                cell.setCellValue(text);            // 超长/前导零：按文本保留，避免精度丢失
                styles.apply(sheet, rowIdx, colIdx, center, "@");
                return;
            }
            String format = numberFormat(text);
            if (isInteger) {
                cell.setCellValue(Long.parseLong(text));
            } else {
                cell.setCellValue(Double.parseDouble(text));
            }
            styles.apply(sheet, rowIdx, colIdx, center, format);
        } else {
            cell.setCellValue(text);
            styles.apply(sheet, rowIdx, colIdx, center, "@");
        }
    }

    private String numberFormat(String raw) {
        if (raw.toUpperCase().contains("E")) {
            return "0.00E+00";
        }
        int dot = raw.indexOf('.');
        if (dot >= 0) {
            return "0." + "0".repeat(raw.length() - dot - 1);
        }
        return "0";
    }

    /** 样式池：按「对齐 + 数字格式」惰性缓存在同一个 workbook 内复用。 */
    private static final class StyleBank {

        private final XSSFWorkbook wb;
        private final Map<String, CellStyle> body = new HashMap<>();
        private final CellStyle head;
        private final CellStyle section;
        private final CellStyle title;
        private final CellStyle wrap;
        private final CellStyle reportKey;
        private final CellStyle reportValue;

        StyleBank(XSSFWorkbook wb) {
            this.wb = wb;
            this.head = headStyle();
            this.section = sectionStyle();
            this.title = titleStyle();
            this.wrap = borderedWrap();
            this.reportKey = reportKeyStyle();
            this.reportValue = reportValueStyle();
        }

        CellStyle head() {
            return head;
        }

        CellStyle section() {
            return section;
        }

        CellStyle title() {
            return title;
        }

        CellStyle wrap() {
            return wrap;
        }

        CellStyle reportKey() {
            return reportKey;
        }

        CellStyle reportValue() {
            return reportValue;
        }

        /** 表头行：深蓝底、白字、加粗、居中，行高 24。 */
        void head(Sheet sheet, int rowIdx, List<String> titles) {
            Row row = sheet.createRow(rowIdx);
            for (int i = 0; i < titles.size(); i++) {
                Cell cell = row.createCell(i);
                cell.setCellValue(titles.get(i));
                cell.setCellStyle(head);
            }
            sheet.getRow(rowIdx).setHeightInPoints(24f);
        }

        /** 设置某格样式；{@code format} 为空表示不设数字格式（沿用通用）。 */
        void apply(Sheet sheet, int rowIdx, int colIdx, boolean center, String format) {
            Row row = sheet.getRow(rowIdx);
            if (row == null) {
                row = sheet.createRow(rowIdx);
            }
            Cell cell = row.getCell(colIdx);
            if (cell == null) {
                cell = row.createCell(colIdx);
            }
            cell.setCellStyle(body(center, format));
        }

        CellStyle body(boolean center, String format) {
            String key = (center ? "c" : "l") + "|" + format;
            return body.computeIfAbsent(key, k -> {
                XSSFCellStyle style = wb.createCellStyle();
                style.setFont(font(false));
                style.setBorderTop(BorderStyle.THIN);
                style.setBorderBottom(BorderStyle.THIN);
                style.setBorderLeft(BorderStyle.THIN);
                style.setBorderRight(BorderStyle.THIN);
                style.setTopBorderColor(grey());
                style.setBottomBorderColor(grey());
                style.setLeftBorderColor(grey());
                style.setRightBorderColor(grey());
                style.setAlignment(center ? HorizontalAlignment.CENTER : HorizontalAlignment.LEFT);
                style.setVerticalAlignment(VerticalAlignment.CENTER);
                if (format != null) {
                    style.setDataFormat(wb.createDataFormat().getFormat(format));
                }
                style.setWrapText(true);
                return style;
            });
        }

        private CellStyle headStyle() {
            XSSFCellStyle style = wb.createCellStyle();
            XSSFFont f = font(true);
            f.setColor(new XSSFColor(new byte[]{(byte) 0xFF, (byte) 0xFF, (byte) 0xFF}, null));
            style.setFont(f);
            style.setFillForegroundColor(new XSSFColor(HEAD_FILL, null));
            style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            style.setAlignment(HorizontalAlignment.CENTER);
            style.setVerticalAlignment(VerticalAlignment.CENTER);
            applyBorders(style);
            return style;
        }

        private CellStyle sectionStyle() {
            XSSFCellStyle style = wb.createCellStyle();
            style.setFont(font(true));
            style.setFillForegroundColor(new XSSFColor(SECTION_FILL, null));
            style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            style.setVerticalAlignment(VerticalAlignment.CENTER);
            applyBorders(style);
            return style;
        }

        private CellStyle titleStyle() {
            XSSFCellStyle style = wb.createCellStyle();
            XSSFFont f = wb.createFont();
            f.setFontName(FONT);
            f.setFontHeightInPoints((short) 12);
            f.setBold(true);
            f.setColor(new XSSFColor(HEAD_FILL, null));
            style.setFont(f);
            return style;
        }

        private CellStyle borderedWrap() {
            XSSFCellStyle style = wb.createCellStyle();
            style.setFont(font(false));
            style.setWrapText(true);
            style.setVerticalAlignment(VerticalAlignment.TOP);
            applyBorders(style);
            return style;
        }

        private CellStyle reportKeyStyle() {
            XSSFCellStyle style = wb.createCellStyle();
            style.setFont(font(true));
            style.setVerticalAlignment(VerticalAlignment.TOP);
            applyBorders(style);
            return style;
        }

        private CellStyle reportValueStyle() {
            XSSFCellStyle style = wb.createCellStyle();
            style.setFont(font(false));
            style.setWrapText(true);
            style.setVerticalAlignment(VerticalAlignment.TOP);
            applyBorders(style);
            return style;
        }

        private void applyBorders(XSSFCellStyle style) {
            style.setBorderTop(BorderStyle.THIN);
            style.setBorderBottom(BorderStyle.THIN);
            style.setBorderLeft(BorderStyle.THIN);
            style.setBorderRight(BorderStyle.THIN);
            style.setTopBorderColor(grey());
            style.setBottomBorderColor(grey());
            style.setLeftBorderColor(grey());
            style.setRightBorderColor(grey());
        }

        private XSSFFont font(boolean bold) {
            XSSFFont f = wb.createFont();
            f.setFontName(FONT);
            f.setFontHeightInPoints((short) 10);
            f.setBold(bold);
            return f;
        }

        private XSSFColor grey() {
            return new XSSFColor(BORDER_RGB, null);
        }
    }
}
