package com.motorsizer.app.importing.render;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 样册 PDF → 参数表结构（pdf2excel.py 的 Java 移植）。
 *
 * <p>纯程序化版面解析，不用 OCR、不用视觉模型：
 * <ol>
 *   <li>表头定位：含「符号 + 单位」的行视为一格表的表头（只有「单位 + 接法」也接受）；</li>
 *   <li>列边界：由表头 token 的中心线取中点，自适应 5 列 / 10 列 / 任意列；</li>
 *   <li>配置列：单型号表 → 串联/并联；多型号对照表 → 型号行覆盖哪些接法列就归给哪个型号；</li>
 *   <li>分节：表头左侧的分节名 + 表内浅蓝底色块所在行；</li>
 *   <li>脚注：表块下方行首的小字号独立数字为编号，续行按左缘与行距拼接。</li>
 * </ol>
 *
 * <p>方法名与脚本里的函数一一对应（{@code group_lines} → {@link #groupLines} 等），
 * 改算法时请同步两份实现，并用真实样册回归（`CatalogParserTest`）。
 */
@Component
public class CatalogParser {

    private static final Pattern FOOTNOTE_NO = Pattern.compile("\\d{1,2}");
    private static final Pattern NOTE_NO = Pattern.compile("[1-9]");
    private static final Pattern ENGLISH_NOTE_START = Pattern.compile("^\\d\\.[A-Za-z].*");
    /** 行首脚注编号：单个非零数字，后面紧跟中文或整行只有数字（样册把编号与正文画在同一条基线上）。 */
    private static final Pattern LEADING_NOTE = Pattern.compile("^([1-9])(?=[\\u4e00-\\u9fff]|$)");

    /** 图纸、曲线图例等非脚注文本的行首词（出现在脚注区但应跳过）。 */
    private static final List<String> DRAWING_WORDS = List.of(
            "安装示意图", "正常安装", "倒装", "侧装", "尺寸图", "转矩(", "转速(",
            "直流母线", "持续转矩", "峰值转矩", "转矩（", "转速（");

    private final PdfPageScanner scanner;

    public CatalogParser(PdfPageScanner scanner) {
        this.scanner = scanner;
    }

    // ---------------------------------------------------------------- 入口

    public ParsedCatalog parse(Path pdf) throws IOException {
        try (PDDocument doc = PDDocument.load(pdf.toFile())) {
            List<CatalogTable> tables = new ArrayList<>();
            Map<Integer, String> notes = new LinkedHashMap<>();
            List<Integer> skipped = new ArrayList<>();
            // 分节 → 数据行数（含被丢弃的分节，用于「识别报告」里说明过滤掉多少）
            Map<String, Integer> sectionRows = new LinkedHashMap<>();
            int chars = 0;

            for (int pno = 1; pno <= doc.getNumberOfPages(); pno++) {
                PdfPageScanner.PageScan scan = scanner.scan(doc, pno);
                chars += scan.charCount();
                List<FillBand> bands = scan.bands();

                List<TextLine> probe = groupLines(withinX(scan.runs(), Double.MAX_VALUE));
                List<Integer> hs = headersIn(probe);
                if (hs.isEmpty()) {
                    skipped.add(pno);
                    continue;
                }

                // 表右边界：优先取覆盖表头行的色块右缘（= 表格宽度），退化为最窄色块
                double headerY = probe.get(hs.get(0)).oy();
                double right = 0;
                for (FillBand b : bands) {
                    if (b.y0() - 20 <= headerY && headerY <= b.y1() + 6) {
                        right = Math.max(right, b.x1());
                    }
                }
                if (right <= 0 && !bands.isEmpty()) {
                    right = bands.stream().mapToDouble(FillBand::x1).min().orElse(0);
                }
                if (right <= 0) {
                    right = probe.get(hs.get(0)).runs().stream()
                            .mapToDouble(TextRun::x1).max().orElse(0) + 15;
                }

                List<TextLine> lines = groupLines(withinX(scan.runs(), right));
                List<Integer> headers = headersIn(lines);

                int got = 0;
                for (int hi : headers) {
                    Columns cols = buildColumns(lines.get(hi), right);
                    Map<String, List<Integer>> groups = modelGroups(lines, hi, cols);
                    String model = namesFromTitle(lines, hi, groups);
                    Block block = parseBlock(lines, hi, cols);
                    if (block.rows.stream().filter(r -> !r.name.isEmpty()).count()
                            < LayoutRules.MIN_NAMED_ROWS) {
                        continue;
                    }
                    List<TableRow> rows = attachContinuations(block.rows);
                    rows = markSections(rows, lines.get(hi), bands);
                    List<String> labels = columnLabels(cols, groups);
                    rows = mergeLabelRows(rows);
                    rows = mergeCells(rows);
                    // 分节过滤放在所有行合并之后：这样报告里的「分节明细/丢弃行数」与
                    // 「参数行数」口径一致（都是合并后的行数），不会出现两边加起来对不上的情况。
                    rows = keepWantedSections(rows, sectionRows);
                    if (rows.stream().allMatch(r -> r.values.isEmpty())) {
                        continue;
                    }
                    tables.add(new CatalogTable(pno, model, labels, cols.configTexts(), rows));
                    notes.putAll(parseFootnotes(lines, block.end));
                    got++;
                }
                if (got == 0) {
                    skipped.add(pno);
                }
            }
            // 有文字层的判据：整本字符量够多，或至少解析出了参数表（单页短样册字符数可能很少）
            boolean hasText = chars > 500 || !tables.isEmpty();
            return new ParsedCatalog(pdf.toString(), doc.getNumberOfPages(), chars, hasText,
                    tables, notes, skipped, sectionRows);
        }
    }

    /** 丢弃左侧竖排装饰文字；给了右边界时再丢弃表格右侧的图表文字。 */
    private List<TextRun> withinX(List<TextRun> runs, double rightEdge) {
        boolean bounded = rightEdge != Double.MAX_VALUE;
        List<TextRun> out = new ArrayList<>();
        for (TextRun r : runs) {
            if (r.x0() < LayoutRules.X_MIN) {
                continue;
            }
            if (bounded && r.x1() > rightEdge + 2) {
                continue;
            }
            out.add(r);
        }
        return out;
    }

    // ---------------------------------------------------------------- 行与表头

    /** 按主字号文字的基线聚行；下标/上标（4pt 左右）就近吸附到最近的主行。 */
    List<TextLine> groupLines(List<TextRun> runs) {
        List<TextRun> main = new ArrayList<>();
        List<TextRun> mini = new ArrayList<>();
        for (TextRun r : runs) {
            if (r.size() >= LayoutRules.SUBSCRIPT_SIZE) {
                main.add(r);
            } else {
                mini.add(r);
            }
        }
        main.sort(Comparator.comparingDouble(TextRun::oy));

        List<double[]> bases = new ArrayList<>();
        List<List<TextRun>> buckets = new ArrayList<>();
        for (TextRun r : main) {
            if (!buckets.isEmpty()
                    && Math.abs(r.oy() - bases.get(bases.size() - 1)[0]) <= LayoutRules.LINE_TOLERANCE) {
                buckets.get(buckets.size() - 1).add(r);
            } else {
                bases.add(new double[]{r.oy()});
                buckets.add(new ArrayList<>(List.of(r)));
            }
        }
        for (TextRun r : mini) {
            if (buckets.isEmpty()) {
                bases.add(new double[]{r.oy()});
                buckets.add(new ArrayList<>(List.of(r)));
                continue;
            }
            int best = 0;
            double bestDiff = Double.MAX_VALUE;
            for (int i = 0; i < buckets.size(); i++) {
                double diff = Math.abs(bases.get(i)[0] - r.oy());
                if (diff < bestDiff) {
                    bestDiff = diff;
                    best = i;
                }
            }
            if (bestDiff <= LayoutRules.SUBSCRIPT_ATTACH) {
                buckets.get(best).add(r);
            }
        }

        List<TextLine> lines = new ArrayList<>();
        for (int i = 0; i < buckets.size(); i++) {
            List<TextRun> rs = new ArrayList<>(buckets.get(i));
            rs.sort(Comparator.comparingDouble(TextRun::x0));
            lines.add(new TextLine(bases.get(i)[0], List.copyOf(rs)));
        }
        lines.sort(Comparator.comparingDouble(TextLine::oy));
        return lines;
    }

    /** 含「符号 + 单位」的行视为表头；只含「单位 + 接法」的也接受。 */
    private List<Integer> headersIn(List<TextLine> lines) {
        List<Integer> out = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            TextLine ln = lines.get(i);
            if (ln.minX() < LayoutRules.X_MIN) {
                continue;
            }
            var tokens = ln.tokens();
            if (tokens.contains("符号") && tokens.contains("单位")) {
                out.add(i);
            } else if (tokens.contains("单位") && tokens.stream().anyMatch(LayoutRules.CONFIG_WORDS::contains)) {
                out.add(i);
            }
        }
        return out;
    }

    // ---------------------------------------------------------------- 列

    record Column(String name, double lo, double hi) {
    }

    record Columns(List<Column> columns, List<TextRun> configs) {

        String at(double cx) {
            for (Column c : columns) {
                if (c.lo() <= cx && cx < c.hi()) {
                    return c.name();
                }
            }
            return null;
        }

        List<String> configTexts() {
            return configs.stream().map(TextRun::token).toList();
        }
    }

    /** 由表头 token 的中心线推导列边界。 */
    Columns buildColumns(TextLine header, double rightEdge) {
        Map<String, TextRun> byText = new LinkedHashMap<>();
        for (TextRun r : header.runs()) {
            byText.putIfAbsent(r.token(), r);
        }
        TextRun sym = byText.get("符号");
        TextRun unit = byText.get("单位");
        // 接法列：表头里位于「单位」右侧的所有 token。
        // 不能只认 CONFIG_WORDS（串联/并联/单列/双列）——样册也用 L/H、A/B 这类字母标列，
        // 认不出就退化成「整表一列『值』」，数值会被并进单位列（AKH100 系列就是这样丢的）。
        double rightOfUnit = unit != null ? unit.x1() : (sym != null ? sym.x1() : 0);
        List<TextRun> configs = new ArrayList<>();
        for (TextRun r : header.runs()) {
            if (r.x0() > rightOfUnit + 1 && !r.token().isEmpty()) {
                configs.add(r);
            }
        }
        configs.sort(Comparator.comparingDouble(TextRun::cx));

        if (configs.isEmpty()) {                       // 无接法列：单位右侧整体当一列
            TextRun right = unit != null ? unit : (sym != null ? sym : header.runs().get(header.runs().size() - 1));
            configs = List.of(TextRun.of("值", right.x1() + 6, rightEdge, header.oy(), right.size()));
        }

        TextRun anchor = sym != null ? sym : unit;
        if (anchor == null) {
            anchor = header.runs().get(0);
        }
        double leftEdge = anchor.x0() - 2.0;

        List<Column> cols = new ArrayList<>();
        cols.add(new Column("name", 0.0, leftEdge));
        double lo = leftEdge;
        if (sym != null && unit != null) {
            double mid = (sym.cx() + unit.cx()) / 2;
            cols.add(new Column("sym", lo, mid));
            lo = mid;
        }
        double firstCfgLeft = configs.size() > 1
                ? configs.get(0).cx() - (configs.get(1).cx() - configs.get(0).cx()) / 2
                : configs.get(0).cx() - 10;
        if (unit != null) {
            double hi = Math.max(firstCfgLeft, lo + 1);
            cols.add(new Column("unit", lo, hi));
            lo = hi;
        }
        for (int i = 0; i < configs.size(); i++) {
            double hi = i + 1 < configs.size()
                    ? (configs.get(i).cx() + configs.get(i + 1).cx()) / 2
                    : rightEdge;
            cols.add(new Column("v" + i, lo, hi));
            lo = hi;
        }
        return new Columns(List.copyOf(cols), List.copyOf(configs));
    }

    /** 表头上方一行里若有多列型号名，给出 型号 → 接法列下标集合。 */
    Map<String, List<Integer>> modelGroups(List<TextLine> lines, int headerIndex, Columns cols) {
        if (headerIndex == 0) {
            return Map.of();
        }
        List<TextRun> above = lines.get(headerIndex - 1).runs();
        List<TextRun> cand = new ArrayList<>();
        for (TextRun r : above) {
            if (LayoutRules.MODEL_PATTERN.matcher(r.token()).matches()) {
                cand.add(r);
            }
        }
        if (cand.size() < 2 || cand.size() != above.size()) {
            return Map.of();
        }
        Map<String, List<Integer>> groups = new LinkedHashMap<>();
        for (TextRun s : cand) {
            List<Integer> idxs = new ArrayList<>();
            for (int i = 0; i < cols.columns().size(); i++) {
                Column c = cols.columns().get(i);
                double center = (c.lo() + c.hi()) / 2;
                if (c.name().startsWith("v") && s.x0() - 4 <= center && center <= s.x1() + 4) {
                    idxs.add(i);
                }
            }
            groups.put(s.token(), idxs);
        }
        return groups;
    }

    /** 型号名：多列型号 → 公共前缀；否则取表头上方最近的型号样式 token。 */
    String namesFromTitle(List<TextLine> lines, int headerIndex, Map<String, List<Integer>> groups) {
        if (!groups.isEmpty()) {
            List<String> names = new ArrayList<>(groups.keySet());
            String pref = names.get(0);
            for (String n : names.subList(1, names.size())) {
                while (!pref.isEmpty() && !n.startsWith(pref)) {
                    pref = pref.substring(0, pref.length() - 1);
                }
            }
            pref = rstrip(pref, "-/ ");
            for (String sep : List.of("-", "/")) {
                int idx = pref.lastIndexOf(sep);
                if (idx >= 0 && pref.length() - idx - 1 <= 2) {
                    pref = pref.substring(0, idx);
                }
            }
            return pref.isEmpty() ? names.get(0) : pref;
        }
        for (int i = headerIndex - 1; i >= 0; i--) {
            for (TextRun s : lines.get(i).runs()) {
                if (LayoutRules.MODEL_PATTERN.matcher(s.token()).matches()) {
                    return s.token();
                }
            }
        }
        return "未命名型号";
    }

    private List<String> columnLabels(Columns cols, Map<String, List<Integer>> groups) {
        List<String> labels = new ArrayList<>();
        for (int i = 0; i < cols.configs().size(); i++) {
            String key = "v" + i;
            String owner = null;
            for (Map.Entry<String, List<Integer>> e : groups.entrySet()) {
                for (int j : e.getValue()) {
                    if (j < cols.columns().size() && cols.columns().get(j).name().equals(key)) {
                        owner = e.getKey();
                        break;
                    }
                }
                if (owner != null) {
                    break;
                }
            }
            String prefix = owner == null ? "" : owner;
            String token = cols.configs().get(i).token();
            // 中文接法直接粘（AKM200-W-B8 + 并联）；两侧都是 ASCII 字母数字时用 - 隔开
            // （AKH100-N-B2 + L → …-B2-L），否则 "B2L" 糊在一起分不清是型号还是接法。
            String glue = !prefix.isEmpty() && !token.isEmpty()
                    && isAsciiAlnum(prefix.charAt(prefix.length() - 1))
                    && isAsciiAlnum(token.charAt(0)) ? "-" : "";
            String label = (prefix + glue + token).strip();
            labels.add(label.isEmpty() ? "列" + (i + 1) : label);
        }
        return labels;
    }

    private static boolean isAsciiAlnum(char c) {
        return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z');
    }

    // ---------------------------------------------------------------- 表体

    record CellText(String text, String marks) {
        static final CellText EMPTY = new CellText("", "");
    }

    /** 解析一格表块。 */
    record Block(List<TableRow> rows, int end) {
    }

    Block parseBlock(List<TextLine> lines, int headerIndex, Columns cols) {
        int end = lines.size();
        for (int j = headerIndex + 1; j < lines.size(); j++) {
            if (isFootnoteStart(lines.get(j))
                    || lines.get(j).oy() - lines.get(j - 1).oy() > LayoutRules.BLOCK_LINE_GAP) {
                end = j;
                break;
            }
        }

        List<TableRow> rows = new ArrayList<>();
        for (int j = headerIndex + 1; j < end; j++) {
            TextLine ln = lines.get(j);
            Map<String, List<TextRun>> cells = new LinkedHashMap<>();
            for (TextRun r : ln.runs()) {
                String c = cols.at(r.cx());
                if (c == null) {
                    continue;
                }
                cells.computeIfAbsent(c, k -> new ArrayList<>()).add(r);
            }
            Map<String, CellText> rendered = new LinkedHashMap<>();
            for (Map.Entry<String, List<TextRun>> e : cells.entrySet()) {
                rendered.put(e.getKey(), joinCell(e.getValue()));
            }

            TableRow row = new TableRow();
            row.oy = ln.oy();
            row.name = text(rendered, "name");
            row.sym = text(rendered, "sym");
            row.unit = text(rendered, "unit");
            for (Map.Entry<String, CellText> e : rendered.entrySet()) {
                if (e.getKey().startsWith("v") && !e.getValue().text().isEmpty()) {
                    row.values.put(e.getKey(), e.getValue().text());
                }
            }
            StringBuilder foot = new StringBuilder();
            for (CellText c : rendered.values()) {
                foot.append(c.marks());
            }
            row.footnote = foot.toString();
            rows.add(row);
        }
        return new Block(rows, end);
    }

    private String text(Map<String, CellText> cells, String key) {
        CellText c = cells.get(key);
        return c == null ? "" : c.text();
    }

    /** 单元格内多行文字用「；」连接，小字号纯数字抽成脚注编号。 */
    CellText joinCell(List<TextRun> spans) {
        List<TextRun> sorted = new ArrayList<>(spans);
        sorted.sort(Comparator.comparingDouble(TextRun::oy));

        List<List<TextRun>> rows = new ArrayList<>();
        List<Double> bases = new ArrayList<>();
        for (TextRun s : sorted) {
            if (!rows.isEmpty()
                    && Math.abs(s.oy() - bases.get(bases.size() - 1)) <= LayoutRules.CELL_LINE_TOLERANCE) {
                rows.get(rows.size() - 1).add(s);
            } else {
                bases.add(s.oy());
                rows.add(new ArrayList<>(List.of(s)));
            }
        }

        List<String> body = new ArrayList<>();
        StringBuilder marks = new StringBuilder();
        for (List<TextRun> line : rows) {
            line.sort(Comparator.comparingDouble(TextRun::x0));
            StringBuilder buf = new StringBuilder();
            for (TextRun s : line) {
                if (s.size() <= LayoutRules.FOOTNOTE_MAX_SIZE
                        && FOOTNOTE_NO.matcher(s.token()).matches()) {
                    marks.append(s.token());
                } else {
                    buf.append(s.text());
                }
            }
            String t = buf.toString().strip();
            if (!t.isEmpty()) {
                body.add(t);
            }
        }
        String txt = String.join("；", body);
        if ("kg·m2".equals(txt)) {
            txt = "kg·m²";
        }
        return new CellText(txt, marks.toString());
    }

    /** 无参数名的行（合并单元格里的第二三行文字）按 y 顺序并入最近的有名行。 */
    List<TableRow> attachContinuations(List<TableRow> rows) {
        List<TableRow> named = new ArrayList<>();
        for (TableRow r : rows) {
            if (!r.name.isEmpty()) {
                named.add(r);
            }
        }
        List<TableRow> keep = new ArrayList<>();
        Map<TableRow, List<Object[]>> extras = new LinkedHashMap<>();
        for (TableRow r : rows) {
            if (!r.name.isEmpty()) {
                keep.add(r);
                continue;
            }
            if (named.isEmpty()) {
                continue;
            }
            TableRow target = null;
            double bestDiff = Double.MAX_VALUE;
            for (TableRow n : named) {
                double diff = Math.abs(n.oy - r.oy);
                if (diff < bestDiff) {
                    bestDiff = diff;
                    target = n;
                }
            }
            if (target == null || bestDiff > LayoutRules.CONTINUATION_ATTACH) {
                continue;
            }
            String txt = r.sym + r.unit + String.join("", r.orderedValues());
            if (!txt.isEmpty()) {
                extras.computeIfAbsent(target, k -> new ArrayList<>()).add(new Object[]{r.oy, txt});
            }
        }

        for (Map.Entry<TableRow, List<Object[]>> e : extras.entrySet()) {
            TableRow target = e.getKey();
            String base = target.unit + String.join("", target.orderedValues());
            List<Object[]> seq = new ArrayList<>();
            seq.add(new Object[]{target.oy, base});
            seq.addAll(e.getValue());
            seq.sort(Comparator.comparingDouble(a -> (double) a[0]));
            List<String> parts = new ArrayList<>();
            for (Object[] item : seq) {
                String t = (String) item[1];
                if (!t.isEmpty()) {
                    parts.add(rstrip(t, ";；"));
                }
            }
            if (parts.size() > 1) {
                target.unit = "";
                target.values.clear();
                target.values.put("v0", String.join("；", parts));
                target.merged = true;
            }
        }
        return keep;
    }

    /** 分节行：表头左侧的分节名 + 表内浅蓝底色块覆盖的行。 */
    List<TableRow> markSections(List<TableRow> rows, TextLine header, List<FillBand> bands) {
        String first = null;
        for (TextRun s : header.runs()) {
            String t = s.token();
            if (s.x0() < 100 && !t.isEmpty() && !t.equals("符号") && !t.equals("单位")) {
                first = t;
                break;
            }
        }
        List<TableRow> out = new ArrayList<>();
        if (first != null && (rows.isEmpty() || !rows.get(0).name.equals(first))) {
            TableRow section = new TableRow();
            section.oy = header.oy();
            section.name = first;
            section.kind = TableRow.KIND_SECTION;
            section.section = first;
            out.add(section);
        }
        for (TableRow r : rows) {
            boolean inBand = false;
            for (FillBand b : bands) {
                if (b.y0() - 3 <= r.oy && r.oy <= b.y1() + 3) {
                    inBand = true;
                    break;
                }
            }
            boolean nameOnly = r.sym.isEmpty() && r.unit.isEmpty() && r.values.isEmpty();
            if (inBand && nameOnly) {
                r.kind = TableRow.KIND_SECTION;
                r.section = r.name;
            } else {
                r.kind = TableRow.KIND_ROW;
            }
            out.add(r);
        }
        return out;
    }

    /**
     * 只保留 {@link LayoutRules#KEEP_SECTIONS} 里的分节（2026-11-19 用户裁定：只抓性能参数）。
     *
     * <p>数据行自身不带分节信息，分节靠 {@code KIND_SECTION} 行按顺序表达，因此这里顺序扫描、
     * 维护「当前分节是否保留」；第一个分节之前的部分（型号行、表头区）默认保留。
     * 所有分节的数据行数一并记进 {@code sectionCounts}，供「识别报告」说明被丢掉了哪些。
     */
    List<TableRow> keepWantedSections(List<TableRow> rows, Map<String, Integer> sectionCounts) {
        List<TableRow> out = new ArrayList<>();
        String current = "";
        boolean keep = true;
        for (TableRow r : rows) {
            if (r.isSection()) {
                current = r.name == null ? "" : r.name.strip();
                keep = LayoutRules.KEEP_SECTIONS.contains(current);
                if (keep) {
                    out.add(r);
                }
                continue;
            }
            sectionCounts.merge(current, 1, Integer::sum);
            if (keep) {
                out.add(r);
            }
        }
        return out;
    }

    /** 其他信息里的纵向合并单元格：只有参数名、上下各挂一行数据的，作为分组名。 */
    List<TableRow> mergeLabelRows(List<TableRow> rows) {
        List<TableRow> result = new ArrayList<>();
        int i = 0;
        while (i < rows.size()) {
            TableRow r = rows.get(i);
            if (TableRow.KIND_ROW.equals(r.kind) && r.values.isEmpty()
                    && r.sym.isEmpty() && r.unit.isEmpty()) {
                List<TableRow> kids = new ArrayList<>();
                for (TableRow x : rows) {
                    if (x == r || Math.abs(x.oy - r.oy) > LayoutRules.LABEL_ATTACH
                            || !TableRow.KIND_ROW.equals(x.kind)) {
                        continue;
                    }
                    if (!x.values.isEmpty() || !x.sym.isEmpty() || !x.unit.isEmpty()) {
                        kids.add(x);
                    }
                }
                if (!kids.isEmpty()) {
                    for (TableRow k : kids) {
                        k.parent = r.name;
                    }
                    i++;
                    continue;
                }
            }
            result.add(r);
            i++;
        }
        for (TableRow r : result) {
            if (r.parent != null) {
                r.name = r.parent + "(" + r.name + ")";
            }
        }
        return result;
    }

    /** 无符号列的行 = 跨列合并单元格：右侧内容拼成一格。 */
    List<TableRow> mergeCells(List<TableRow> rows) {
        for (TableRow r : rows) {
            if (!TableRow.KIND_ROW.equals(r.kind) || !r.sym.isEmpty()) {
                continue;
            }
            List<String> vals = new ArrayList<>();
            for (String v : r.orderedValues()) {
                if (!v.isEmpty()) {
                    vals.add(v);
                }
            }
            if (vals.isEmpty() && r.unit.isEmpty()) {
                continue;
            }
            String txt = String.join("", vals);
            if (!r.unit.isEmpty() && !vals.isEmpty() && endsWithChinese(r.unit)
                    && (vals.get(0).startsWith("(") || vals.get(0).startsWith("（"))) {
                txt = r.unit + " " + txt;           // 原样册在中文与括号之间留一个空格
            } else {
                txt = r.unit + txt;
            }
            r.unit = "";
            r.values.clear();
            r.values.put("v0", txt);
            r.merged = true;
        }
        return rows;
    }

    // ---------------------------------------------------------------- 脚注

    private boolean isFootnoteStart(TextLine line) {
        TextRun s = line.first();
        if (s.x0() >= 52) {
            return false;
        }
        String t = s.token();
        return FOOTNOTE_NO.matcher(t).matches() || LEADING_NOTE.matcher(t).find();
    }

    /** 脚注：行首小字号、x 在正文左缘的单个数字为编号；续行限同一左缘且行距 &lt; 13。 */
    Map<Integer, String> parseFootnotes(List<TextLine> lines, int start) {
        Map<Integer, String> notes = new LinkedHashMap<>();
        Integer current = null;
        double prevY = -99;
        for (int i = start; i < lines.size(); i++) {
            TextLine ln = lines.get(i);
            TextRun head = ln.first();
            List<TextRun> core = new ArrayList<>();
            for (TextRun x : ln.runs()) {
                if (x.x0() < LayoutRules.FOOTNOTE_BODY_RIGHT) {
                    core.add(x);
                }
            }
            StringBuilder sb = new StringBuilder();
            for (TextRun x : core) {
                sb.append(x.text());
            }
            String txt = sb.toString().strip();
            if (txt.isEmpty() || txt.startsWith("相关参数规格")) {
                continue;
            }
            if (txt.startsWith("Notes") || ENGLISH_NOTE_START.matcher(txt).matches()) {
                break;                              // 进入图纸英文注记区
            }
            if (startsWithDrawingWord(txt)) {
                continue;                           // 图注、曲线图例
            }
            int[] head0 = footnoteHead(head, txt);
            if (head0 != null) {
                // 编号可能独立成 run，也可能与正文粘在同一个 run 里：统一按匹配位置剥掉
                String noteBody = txt.substring(Math.min(head0[1], txt.length())).strip();
                if (!hasChinese(noteBody) && countLetters(noteBody) < 6) {
                    continue;                       // 数字/尺寸串不是脚注正文
                }
                current = head0[0];
                notes.put(current, noteBody);
            } else if (current != null
                    && head.x0() >= LayoutRules.FOOTNOTE_BODY_MIN_X
                    && head.x0() < LayoutRules.FOOTNOTE_BODY_MAX_X
                    && ln.oy() - prevY < 13) {
                notes.merge(current, txt, String::concat);
            }
            prevY = ln.oy();
        }
        return notes;
    }

    /** 脚注行首编号：返回 {@code {编号, 正文起始下标}}；不是脚注行返回 null。 */
    private int[] footnoteHead(TextRun head, String txt) {
        if (head.x0() < LayoutRules.FOOTNOTE_NO_MIN_X
                || head.x0() >= LayoutRules.FOOTNOTE_NO_MAX_X) {
            return null;
        }
        Matcher m = LEADING_NOTE.matcher(txt);
        if (m.find()) {
            return new int[]{Integer.parseInt(m.group(1)), m.end()};
        }
        if (NOTE_NO.matcher(head.token()).matches()) {
            return new int[]{Integer.parseInt(head.token()),
                    Math.min(head.token().length(), txt.length())};
        }
        return null;
    }

    private boolean startsWithDrawingWord(String txt) {
        for (String w : DRAWING_WORDS) {
            if (txt.startsWith(w)) {
                return true;
            }
        }
        return false;
    }

    // ---------------------------------------------------------------- 小工具

    private static String rstrip(String s, String chars) {
        int end = s.length();
        while (end > 0 && (chars.indexOf(s.charAt(end - 1)) >= 0 || Character.isWhitespace(s.charAt(end - 1)))) {
            end--;
        }
        return s.substring(0, end);
    }

    private static boolean endsWithChinese(String s) {
        if (s.isEmpty()) {
            return false;
        }
        char c = s.charAt(s.length() - 1);
        return c >= 0x4E00 && c <= 0x9FFF;
    }

    private static boolean hasChinese(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= 0x4E00 && c <= 0x9FFF) {
                return true;
            }
        }
        return false;
    }

    private static int countLetters(String s) {
        int n = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z')) {
                n++;
            }
        }
        return n;
    }

    /** 去重保序的型号列表（写 Excel 分页用）。 */
    static List<String> distinctModels(List<CatalogTable> tables) {
        LinkedHashSet<String> set = new LinkedHashSet<>();
        for (CatalogTable t : tables) {
            set.add(t.model());
        }
        return List.copyOf(set);
    }
}
