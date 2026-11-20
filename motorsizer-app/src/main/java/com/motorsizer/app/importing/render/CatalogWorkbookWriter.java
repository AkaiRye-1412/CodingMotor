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
    /**
     * 接法后缀：中文 {@code 串联/并联} 与英文 {@code Series/Serial/Parallel} 都要压成 {@code -S/-P}。
     *
     * <p>锚定在<b>字符串末尾</b>（配置名就是整个接法词，中间夹着的词不是接法标记）：
     * {@code Parallel Shaft} 这类词组因此不会被误缩写。也不能用 {@code \b}——
     * Java 的 {@code \b} 基于 ASCII 的 {@code \w}，跟在中文后面永远不成立。
     */
    private static final Pattern CONFIG_SUFFIX = Pattern.compile(
            "-?(?:串联|并联|series|serial|parallel)$", Pattern.CASE_INSENSITIVE);

    /**
     * 兜底列名：拼型号时丢弃，避免出现「AXD120-71值」「CRM0013-Value」。
     *
     * <p>{@code Value / Typical / Nominal} 也要算进来——单配置图纸的参数表只有一列
     * {@code Value}，它描述的是「值这一列」，不是型号变体。
     */
    private static final Pattern FALLBACK_LABEL = Pattern.compile(
            "(?i)v\\d+|列\\d+|值|values?|typ(?:ical)?|nominal|spec(?:ification)?s?|rating");

    /** Excel 工作表名禁用字符 {@code [ ] : * ? / \}；首尾的 {@code '} 也不允许。 */
    private static final Pattern SHEET_NAME_ILLEGAL = Pattern.compile("[\\[\\]:*?/\\\\]");
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /**
     * 写出结果（统计列进 job.json，供前端展示）。
     *
     * @param dataRows    「参数汇总」的数据行数（冷却变体拆开后按拆出的型号各算一份）
     * @param wide        源表是否为「串联 | 并联」两列版式（只用于报告标注）
     * @param splitModels 按冷却方式拆过的源型号数（0 表示本样册没有冷却变体）
     */
    public record Result(int dataRows, boolean wide, int splitModels) {
    }

    public Result write(ParsedCatalog data, Path target) throws IOException {
        if (target.getParent() != null) {
            Files.createDirectories(target.getParent());
        }
        // 冷却变体：型号 AUM4-S1-S 的参数若按「自冷/气冷/水冷」分列，拆成 AUM4-S1-S / -A / -W 三个型号
        CoolingVariants.Expansion expansion = CoolingVariants.expand(data.tables());
        try (XSSFWorkbook wb = new XSSFWorkbook();
             OutputStream out = Files.newOutputStream(target)) {
            StyleBank styles = new StyleBank(wb);
            int dataRows = writeSummarySheet(wb, styles, expansion.tables());
            writeModelSheets(wb, styles, expansion.tables());
            writeFootnoteSheet(wb, styles, data);
            writeReportSheet(wb, styles, data, expansion, dataRows);

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
            return new Result(dataRows, isWide(data.tables()), expansion.splitCount());
        }
    }

    // ---------------------------------------------------------------- 参数汇总

    /** 源表是不是「串联 | 并联」两列版式：只用于报告标注，输出结构一律是单列「值」。 */
    private boolean isWide(List<CatalogTable> tables) {
        // 注意不能用 Set.copyOf(labels) 去重——两列同名时它会抛 IllegalArgumentException，
        // 而「两列都叫 Value」在单配置图纸里很常见。
        return tables.stream().allMatch(t ->
                t.labels().size() <= 2 && t.labels().stream()
                        .allMatch(l -> l.equals("串联") || l.equals("并联")));
    }

    private int writeSummarySheet(XSSFWorkbook wb, StyleBank styles, List<CatalogTable> tables) {
        Sheet ws = wb.createSheet("参数汇总");
        List<String> heads = List.of("型号", "类别", "参数名", "符号", "单位", "值");
        styles.head(ws, 0, heads);

        int r = 1;
        for (CatalogTable table : tables) {
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
                    // 型号列 = 型号 + 该接法（串联 → -S、并联 → -P）+ 冷却后缀（气冷 -A / 水冷 -W）
                    set(ws, r, 0,
                            CoolingVariants.appendSuffix(mergeModelConfig(table.model(), e.getKey()),
                                    table.variant()), styles, true);
                    // 行自带分节时以行为准：磁轨规格小表补进来的惯量行标「磁轨规格」，
                    // 否则会被上一个保留分节（性能参数）冒名顶替，看不出数据来源
                    set(ws, r, 1, row.section == null || row.section.isEmpty() ? section : row.section,
                            styles, true);
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
        return r - 1;
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
        if (FALLBACK_LABEL.matcher(c).matches()) {
            c = "";                                // 「值 / 列N / vN / Value」是兜底列名，本身没有型号含义
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
     * 把型号值里的接法压成后缀：{@code AUM2-S2串联 → AUM2-S2-S}、{@code ACD85-P-15-Parallel → ACD85-P-15-P}。
     * <p>已经是 {@code -串联} 的不会再叠一个连字符。
     */
    static String compactConfigWord(String value) {
        if (value == null || value.isEmpty()) {
            return value == null ? "" : value;
        }
        Matcher matcher = CONFIG_SUFFIX.matcher(value);
        StringBuilder sb = new StringBuilder(value.length());
        boolean changed = false;
        while (matcher.find()) {
            String text = matcher.group().toLowerCase(java.util.Locale.ROOT);
            String suffix = text.contains("串") || text.contains("series") || text.contains("serial")
                    ? "-S" : "-P";
            matcher.appendReplacement(sb, suffix);
            changed = true;
        }
        if (!changed) {
            return value;                          // 绝大多数型号不含接法，省一次 String 分配
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

    // ---------------------------------------------------------------- 写盘安全阀

    /**
     * 合并单元格的唯一入口：区间不足两格就直接跳过。
     *
     * <p>POI 的 {@code addMergedRegion} 遇到单格区间会抛
     * {@code IllegalArgumentException: Merged region D9 must contain 2 or more cells}。
     * 而「单格合并」在真实图纸里是<b>正常数据形状</b>——只有一列 {@code Value}
     * 的参数表里，{@code row.merged}（原表串联/并联共用一格）就没有第二格可合；
     * 列数为 0 时 {@code 2 + labels.size()} 还会小于起始列。两者都只是「不需要合并」，
     * 不该让整份 Excel 写不出来。
     */
    private static void merge(Sheet sheet, int r1, int c1, int r2, int c2) {
        if (r2 < r1 || c2 <= c1) {
            return;                                   // 单格或列区间倒置：没有可合并的范围
        }
        sheet.addMergedRegion(new CellRangeAddress(r1, r2, c1, c2));
    }

    /** 工作表名：清掉 Excel 禁用字符、限 31 字、不与已有工作表重名。 */
    private static String sheetName(XSSFWorkbook wb, CatalogTable table) {
        String base = sanitizeSheetName(table.outputModel());
        String name = base;
        if (wb.getSheet(name) != null) {
            name = withTail(base, "_" + table.page());
        }
        int suffix = 2;
        while (wb.getSheet(name) != null) {
            name = withTail(base, "_" + table.page() + "_" + suffix++);
        }
        return name;
    }

    /**
     * 型号 → 合法工作表名。型号常含 {@code /}（{@code AKM100-B4/D79}）或 {@code :}，
     * 而 Excel 的工作表名不允许 {@code [ ] : * ? / \}，POI 会直接抛
     * {@code IllegalArgumentException: Sheet name ... is invalid}。
     */
    static String sanitizeSheetName(String raw) {
        String s = raw == null ? "" : SHEET_NAME_ILLEGAL.matcher(raw).replaceAll("-");
        s = s.replaceAll("\\s+", " ").strip();
        while (s.endsWith("'")) {
            s = s.substring(0, s.length() - 1);
        }
        if (s.isEmpty()) {
            s = "型号";
        }
        return s.length() > 24 ? s.substring(0, 24).strip() : s;
    }

    /** 拼工作表名后缀，总长不超过 Excel 的 31 字上限。 */
    private static String withTail(String base, String tail) {
        int room = 31 - tail.length();
        String head = base.length() > room ? base.substring(0, Math.max(1, room)) : base;
        return head + tail;
    }

    // ---------------------------------------------------------------- 型号原版式页

    /**
     * 每型号一页的原版式工作表。传进来的是<b>已按冷却变体拆过</b>的表
     * （{@code AUM4-S1-S} / {@code -A} / {@code -W} 各一页）。
     */
    private void writeModelSheets(XSSFWorkbook wb, StyleBank styles, List<CatalogTable> tables) {
        List<String> models = CatalogParser.distinctModels(tables);
        if (models.size() > 12) {
            return;                                   // 型号过多时只出长表，避免上百个可分页
        }
        for (CatalogTable table : tables) {
            String name = sheetName(wb, table);

            Sheet s = wb.createSheet(name);
            Row title = s.createRow(0);
            Cell titleCell = title.createCell(0);
            titleCell.setCellValue(table.outputModel() + " 参数表（源：第 " + table.page() + " 页）");
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
                    merge(s, rr, 0, rr, hd.size() - 1);
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
                    merge(s, rr, 3, rr, 2 + table.labels().size());
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

    private void writeReportSheet(XSSFWorkbook wb, StyleBank styles, ParsedCatalog data,
                                  CoolingVariants.Expansion expansion, int dataRows) {
        Sheet s = wb.createSheet("识别报告");
        Path src = Path.of(data.source());
        List<String[]> info = List.of(
                new String[]{"项目", "内容"},
                new String[]{"源文件", String.valueOf(src.getFileName())},
                new String[]{"PDF 类型", pdfType(data)},
                new String[]{"总页数", String.valueOf(data.pageCount())},
                new String[]{"识别出参数表的页", String.valueOf(data.parsedPageCount())},
                new String[]{"参数表数量", String.valueOf(data.tables().size())},
                new String[]{"型号数", String.valueOf(data.modelCount())},
                new String[]{"参数行数（保留后）", String.valueOf(data.parameterRows())},
                new String[]{"保留分节", String.join("、", LayoutRules.KEEP_SECTIONS)},
                new String[]{"分节明细", sectionSummary(data)},
                new String[]{"丢弃行数", String.valueOf(data.droppedRows())},
                new String[]{"不采集参数", LayoutRules.EXCLUDED_PARAM_DESC},
                new String[]{"按采集口径剔除", excludedSummary(data)},
                new String[]{"额外采集参数", keptExtraSummary(data)},
                new String[]{"冷却变体口径", CoolingVariants.rule()},
                new String[]{"冷却变体", variantSummary(data.tables(), expansion)},
                new String[]{"长表数据行", String.valueOf(dataRows)},
                new String[]{"脚注条数", String.valueOf(data.footnotes().size())},
                new String[]{"未识别页", data.skippedPages().isEmpty() ? "无"
                        : String.join("、", data.skippedPages().stream().limit(80).map(String::valueOf).toList())},
                new String[]{"图片型参数表页", data.imageTablePages().isEmpty() ? "无"
                        : String.join("、", data.imageTablePages().stream().limit(80).map(String::valueOf).toList())},
                new String[]{"图片型页说明", data.imageTablePages().isEmpty()
                        ? "未发现「标题是参数表、内容却是图片」的页"
                        : "上述页的内容以位图形式嵌入（整页截图/高清图片），没有文字层，"
                        + "自动 OCR 也没能提取出可用的参数行；如需这些数据，请提供更清晰的版本，"
                        + "或对照原图手工录入"},
                new String[]{"OCR 识别页", data.ocrPages().isEmpty() ? "无"
                        : String.join("、", data.ocrPages().stream().limit(80).map(String::valueOf).toList())},
                new String[]{"OCR 页说明", data.ocrPages().isEmpty()
                        ? "本次结果全部来自 PDF 文字层，字符级精确"
                        : "上述页的参数表是位图，数据由 PP-OCR 从渲染图中识别后再走同一套版面解析；"
                        + "字号与个别字符（如 0/O、1/l、°/o）可能误判，导入前请重点核对这几页"},
                new String[]{"识别方式", "坐标级版面解析（表头中心线定列、底色块定分节）；"
                        + "参数表为位图时启用 PP-OCR 识别文字，再走同一套版面逻辑"},
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

    /** 「识别报告」里的 PDF 类型：把「文字版但参数表是图片」单独说清楚，避免与真正的扫描件混淆。 */
    private String pdfType(ParsedCatalog data) {
        boolean ocr = !data.ocrPages().isEmpty();
        if (!data.hasTextLayer()) {
            return ocr ? "扫描件（无文字层），已对参数表页启用 OCR" : "扫描件（无文字层）";
        }
        if (ocr && data.imageTablePages().isEmpty()) {
            return "文字版，部分页的参数表以图片形式嵌入（已 OCR）";
        }
        return data.imageTablePages().isEmpty()
                ? "文字版（含文字层）"
                : "文字版，但参数表以图片形式嵌入";
    }

    /** 「识别报告」里被采集口径剔除的项：删了哪个参数、几行，避免用户以为识别丢行。 */
    private String excludedSummary(ParsedCatalog data) {
        if (data.excludedParams() == null || data.excludedParams().isEmpty()) {
            return "无（本样册未出现「" + LayoutRules.EXCLUDED_PARAM_DESC + "」）";
        }
        List<String> parts = new ArrayList<>();
        for (Map.Entry<String, Integer> e : data.excludedParams().entrySet()) {
            parts.add(e.getKey() + " " + e.getValue() + " 行（已剔除）");
        }
        return String.join("｜", parts);
    }

    /** 「识别报告」里从机械参数分节额外捞取的项：采了哪类、几行，说明它们为什么出现。 */
    private String keptExtraSummary(ParsedCatalog data) {
        if (data.keptExtraParams() == null || data.keptExtraParams().isEmpty()) {
            return "无（本样册机械参数分节里没有「" + LayoutRules.KEEP_EXTRA_PARAM_DESC + "」）";
        }
        List<String> parts = new ArrayList<>();
        for (Map.Entry<String, Integer> e : data.keptExtraParams().entrySet()) {
            parts.add(e.getKey() + " " + e.getValue() + " 行");
        }
        return "已采集（来自机械参数分节）：" + String.join("｜", parts);
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
            Integer extra = data.keptExtraParams() == null ? null : data.keptExtraParams().get(e.getKey());
            if (!kept && extra != null && extra > 0) {
                // 机械参数分节：整节丢弃，但质量/转动惯量这几行被捞出来采集了
                parts.add(name + " " + e.getValue() + " 行（丢弃 " + (e.getValue() - extra)
                        + " 行、采集 " + extra + " 行质量/转动惯量）");
            } else {
                parts.add(name + " " + e.getValue() + " 行" + (kept ? "（保留）" : "（已丢弃）"));
            }
        }
        return String.join("｜", parts);
    }

    /** 「识别报告」里的冷却变体说明：拆了哪些型号、拆出多少，让型号变多这件事可追溯。 */
    private String variantSummary(List<CatalogTable> sources, CoolingVariants.Expansion expansion) {
        if (expansion.splitCount() == 0) {
            return "无（参数名里没有「自冷/气冷/水冷」标注，所有型号原样输出）";
        }
        int added = expansion.tables().size() - sources.size();
        List<String> shown = expansion.splitModels().stream().limit(3).toList();
        return "有 " + expansion.splitCount() + " 个型号按冷却方式拆分，新增 " + added
                + " 个型号（气冷 -A、水冷 -W），拆分后共 "
                + CatalogParser.distinctModels(expansion.tables()).size() + " 个型号；示例："
                + String.join("；", shown) + (expansion.splitCount() > shown.size() ? " 等" : "");
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
