package com.motorsizer.app.importing.render;

import com.motorsizer.app.importing.ocr.OcrEngine;
import com.motorsizer.app.importing.ocr.OcrTextLine;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.awt.image.BufferedImage;
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
 * <p>主链路是纯程序化版面解析，不用视觉模型：
 * <ol>
 *   <li>表头定位：含「符号 + 单位」的行视为一格表的表头（只有「单位 + 接法」也接受；英文 Symbol/Unit 同样认）；</li>
 *   <li>列边界：由表头 token 的中心线取中点，自适应 5 列 / 10 列 / 任意列；</li>
 *   <li>配置列：单型号表 → 串联/并联；多型号对照表 → 型号行覆盖哪些接法列就归给哪个型号；</li>
 *   <li>分节：表头左侧的分节名 + 表内浅蓝底色块所在行；没有底色块的位图表格按分节标题的命名形态判断；</li>
 *   <li>脚注：表块下方行首的小字号独立数字为编号，续行按左缘与行距拼接。</li>
 * </ol>
 *
 * <p>唯一的例外是「参数表本身是位图」的页：文字层里没有表，才渲染该页走 OCR，
 * 再把识别结果当成普通文字行重跑上面这套逻辑（见 {@link #ocrRuns}）。
 *
 * <p>方法名与脚本里的函数一一对应（{@code group_lines} → {@link #groupLines} 等），
 * 改算法时请同步两份实现，并用真实样册回归（`CatalogParserTest`）。
 */
@Component
public class CatalogParser {

    private static final Logger log = LoggerFactory.getLogger(CatalogParser.class);

    private static final Pattern FOOTNOTE_NO = Pattern.compile("\\d{1,2}");
    /** 纯数值 run：用来区分「跟在正文后的脚注号」与「挨在一起的两个参数值」。 */
    private static final Pattern PURE_NUMBER = Pattern.compile("[-+]?\\d+(?:\\.\\d+)?");
    private static final Pattern NOTE_NO = Pattern.compile("[1-9]");
    private static final Pattern ENGLISH_NOTE_START = Pattern.compile("^\\d\\.[A-Za-z].*");
    /** 行首脚注编号：单个非零数字，后面紧跟中文或整行只有数字（样册把编号与正文画在同一条基线上）。 */
    private static final Pattern LEADING_NOTE = Pattern.compile("^([1-9])(?=[\\u4e00-\\u9fff]|$)");

    /** 图纸、曲线图例等非脚注文本的行首词（出现在脚注区但应跳过）。 */
    private static final List<String> DRAWING_WORDS = List.of(
            "安装示意图", "正常安装", "倒装", "侧装", "尺寸图", "转矩(", "转速(",
            "直流母线", "持续转矩", "峰值转矩", "转矩（", "转速（");

    /** OCR 出的字高约为字号的 0.72（同 PDF 的字形框口径）。 */
    private static final double OCR_CAP_RATIO = 0.72;

    private final PdfPageScanner scanner;
    private final OcrEngine ocrEngine;

    /** 测试与无 OCR 场景：只做文字层解析。 */
    public CatalogParser(PdfPageScanner scanner) {
        this(scanner, null);
    }

    @Autowired
    public CatalogParser(PdfPageScanner scanner, OcrEngine ocrEngine) {
        this.scanner = scanner;
        this.ocrEngine = ocrEngine;
    }

    // ---------------------------------------------------------------- 入口

    public ParsedCatalog parse(Path pdf) throws IOException {
        try (PDDocument doc = PDDocument.load(pdf.toFile())) {
            List<CatalogTable> tables = new ArrayList<>();
            Map<Integer, String> notes = new LinkedHashMap<>();
            List<Integer> skipped = new ArrayList<>();
            // 未识别页里「参数表本身是位图」的页：图片铺满页面且页面出现规格类标题
            List<Integer> imageTablePages = new ArrayList<>();
            // 真的跑了 OCR 并成功出表的页：这些页的数据是「看出来的」，不是「读出来的」，要重点核对
            List<Integer> ocrPages = new ArrayList<>();
            // 分节 → 数据行数（含被丢弃的分节，用于「识别报告」里说明过滤掉多少）
            Map<String, Integer> sectionRows = new LinkedHashMap<>();
            // 采集口径下被剔除的参数 → 行数（如「最高线圈温度」），同样进识别报告
            Map<String, Integer> excludedParams = new LinkedHashMap<>();
            // 图片占比达标的页：留给「整本一张表都没有」时的兜底 OCR
            List<Integer> candidates = new ArrayList<>();
            int chars = 0;
            int ocrPagesUsed = 0;
            int pageCount = doc.getNumberOfPages();

            for (int pno = 1; pno <= pageCount; pno++) {
                PdfPageScanner.PageScan scan = scanner.scan(doc, pno);
                chars += scan.charCount();
                boolean ocrPage = false;

                int got = parseTablesInPage(pno, scan.runs(), scan.bands(), false,
                        tables, notes, sectionRows, excludedParams);
                // 文字层里没有表，但这页看着就是「参数表被贴成了图片」：渲染成图跑一次 OCR，
                // 把识别结果当成普通文字行重跑同一套版面逻辑（列边界、分节、脚注都不变）。
                if (got == 0 && ocrPagesUsed < LayoutRules.OCR_MAX_PAGES && isImageSpecPage(scan)) {
                    List<TextRun> ocr = ocrRuns(doc, pno);
                    if (!ocr.isEmpty()) {
                        ocrPagesUsed++;
                        int fromOcr = parseTablesInPage(pno, ocr, scan.bands(), true,
                                tables, notes, sectionRows, excludedParams);
                        if (fromOcr > 0) {
                            got = fromOcr;
                            ocrPage = true;
                        }
                    }
                }
                if (scan.imageCoverage() >= LayoutRules.OCR_FALLBACK_COVERAGE) {
                    candidates.add(pno);
                }
                if (got == 0) {
                    skipped.add(pno);
                    markImageTablePage(imageTablePages, pno, scan);
                } else if (ocrPage) {
                    // 只在真的出表时登记：OCR 跑了但没解析出参数表的页，报「图片型参数表」更有意义
                    ocrPages.add(pno);
                }
            }

            // 兜底的第二遍：整本一张表都没解析出来 —— 这多半是客户图纸或扫描件，
            // 文字层只剩标题栏套话，而「页面有没有 SPECIFICATION 标题」根本判不了（标题本身就在图片里，
            // 如 ACD85 那张 Symbol/Unit 表）。此时改用「图片占比」作唯一判据，逐页试 OCR。
            if (tables.isEmpty() && !candidates.isEmpty()) {
                for (int pno : candidates) {
                    if (ocrPagesUsed >= LayoutRules.OCR_MAX_PAGES) {
                        break;
                    }
                    List<TextRun> ocr = ocrRuns(doc, pno);
                    if (ocr.isEmpty()) {
                        continue;
                    }
                    ocrPagesUsed++;
                    List<FillBand> bands = scanner.scan(doc, pno).bands();
                    if (parseTablesInPage(pno, ocr, bands, true,
                            tables, notes, sectionRows, excludedParams) > 0) {
                        ocrPages.add(pno);
                        skipped.remove(Integer.valueOf(pno));
                        imageTablePages.remove(Integer.valueOf(pno));
                    }
                }
            }

            // 有文字层的判据：整本字符量够多，或至少解析出了参数表（单页短样册字符数可能很少）
            boolean hasText = chars > 500 || !tables.isEmpty();
            return new ParsedCatalog(pdf.toString(), pageCount, chars, hasText,
                    tables, notes, skipped, imageTablePages, ocrPages, sectionRows, excludedParams);
        }
    }

    /**
     * 解析一页里的所有参数表，结果并入入参集合；返回本次解析出的表数。
     *
     * <p>第一遍传文字层的 runs（{@code ocrMode=false}），OCR 页传识别结果（{@code ocrMode=true}）——
     * 两条路径只差「行距上限」与「列边界微调」两个开关，其余逻辑完全共用。
     */
    private int parseTablesInPage(int pno, List<TextRun> runs, List<FillBand> bands, boolean ocrMode,
                                  List<CatalogTable> tables, Map<Integer, String> notes,
                                  Map<String, Integer> sectionRows,
                                  Map<String, Integer> excludedParams) {
        List<TextLine> probe = groupLines(withinX(runs, Double.MAX_VALUE));
        List<Integer> hs = headersIn(probe);
        if (hs.isEmpty()) {
            return 0;
        }

        // 表右边界：优先取覆盖表头行的色块右缘（= 表格宽度）；没有就按表头下方色块推。
        double headerY = probe.get(hs.get(0)).oy();
        TextLine head = probe.get(hs.get(0));
        double right = tableRightEdge(bands, headerY,
                head.runs().stream().mapToDouble(TextRun::x1).max().orElse(0));
        if (right <= 0) {
            if (ocrMode) {
                // 无底色块的位图表格：表头最后一列（Parallel/L）右侧还有整列数值，
                // 而表格右边常并排着曲线图。用表头列距推「下一列」的位置，正好落在值列右缘。
                right = head.runs().stream().mapToDouble(TextRun::x1).max().orElse(0) + columnStep(head);
            } else {
                right = head.runs().stream().mapToDouble(TextRun::x1).max().orElse(0) + 15;
            }
        }

        List<TextLine> lines = groupLines(withinX(runs, right));
        List<Integer> headers = headersIn(lines);

        int got = 0;
        for (int hi : headers) {
            Columns cols = buildColumns(lines.get(hi), right, ocrMode);
            Map<String, List<Integer>> groups = modelGroups(lines, hi, cols);
            String model = namesFromTitle(lines, hi, groups);
            Block block = parseBlock(lines, hi, cols, ocrMode
                    ? LayoutRules.BLOCK_LINE_GAP_OCR : LayoutRules.BLOCK_LINE_GAP);
            if (block.rows.stream().filter(r -> !r.name.isEmpty()).count()
                    < LayoutRules.MIN_NAMED_ROWS) {
                continue;
            }
            List<TableRow> rows = attachContinuations(reuniteBrokenRows(block.rows));
            rows = markSections(rows, lines.get(hi), bands);
            rows = mergeWrappedNames(rows);
            List<String> labels = columnLabels(cols, groups);
            rows = mergeLabelRows(rows);
            rows = mergeCells(rows);
            // 分节过滤放在所有行合并之后：这样报告里的「分节明细/丢弃行数」与
            // 「参数行数」口径一致（都是合并后的行数），不会出现两边加起来对不上的情况。
            rows = keepWantedSections(rows, sectionRows);
            // 再按采集口径剔除个别参数（如最高线圈温度）：用户只需要选型用得上的项
            rows = dropExcludedParameters(rows, excludedParams);
            if (rows.stream().allMatch(r -> r.values.isEmpty())) {
                continue;
            }
            // 说明页正文被当成参数名（样册「力和速度的关系」那类页：左侧一整段正文，
            // 右侧并排一个「说明 / 单位 / 符号」的术语小表，符号列恰好凑齐全套表头词）。
            // 正文句子和参数名在字形上完全不同，按「句读标点 + 超长名字」判，不猜版式。
            if (looksLikeProse(rows)) {
                continue;
            }
            tables.add(new CatalogTable(pno, model, labels, cols.configTexts(), rows));
            notes.putAll(parseFootnotes(lines, block.end));
            got++;
        }
        return got;
    }

    /** 参数名里不该出现的句读标点：出现即说明抓到的是说明文字而非参数名。 */
    private static final String PROSE_PUNCT = "。，；：、！？；,";

    /** 参数名的合理上限（汉字/字母数）；再长基本是一整句话。 */
    private static final int PROSE_NAME_MAX = 28;

    /**
     * 判断这块「表」其实是说明页正文，不是参数表。
     *
     * <p>样册的说明页（如「2. 力和速度的关系」）左侧是一整段正文，右侧并排一个
     * 「说明 / 单位 / 符号」的术语小表 —— 表头词凑得齐全，正文长句就被当成参数名、
     * 术语的字母（F/m/t/v）被当成值，最后产出一个假型号「未命名型号符号」和十几行垃圾。
     *
     * <p>判据只看文字本身，不猜版式：<b>符号列为空</b>的行里，名字带句读标点
     * （{@code 。，；：、}）或超过 {@link #PROSE_NAME_MAX} 字，就算「正文行」；
     * 正文行占到一半以上即整表丢弃。
     *
     * <p>符号列非空时不判 —— 英文图纸的参数名常常又长又带逗号
     * （{@code Continuous force, self-cooled @100°C}），但它们都带符号，误伤不起。
     */
    static boolean looksLikeProse(List<TableRow> rows) {
        List<TableRow> named = new ArrayList<>();
        for (TableRow r : rows) {
            if (!r.isSection() && !r.name().isEmpty()) {
                named.add(r);
            }
        }
        if (named.isEmpty()) {
            return false;
        }
        int prose = 0;
        for (TableRow r : named) {
            if (!r.sym().isEmpty()) {
                continue;
            }
            String n = r.name();
            boolean hit = n.length() >= PROSE_NAME_MAX;
            for (int i = 0; !hit && i < n.length(); i++) {
                hit = PROSE_PUNCT.indexOf(n.charAt(i)) >= 0;
            }
            if (hit) {
                prose++;
            }
        }
        return prose * 2 > named.size();
    }

    /**
     * 单位列归一：整格只有一个横线类字符时统一成半角 {@code -}。
     *
     * <p>「无单位」在样册里写作 {@code -}，OCR 极容易读成汉字「一」（笔画一模一样）；
     * 更差的情况是退化成方块 {@code ■} —— 到了 Excel 里就是
     * {@code Pole Number | 2p | ■ | 8}，看着像乱码。
     */
    static String normalizeUnit(String unit) {
        if (unit.isEmpty()) {
            return unit;
        }
        for (int i = 0; i < unit.length(); i++) {
            if ("-－—–_─一■□▪▫●○◆◇".indexOf(unit.charAt(i)) < 0) {
                return unit;
            }
        }
        return "-";
    }

    /** 上标数字：PDF 把脚注号排成上标时，文字层里就是这些字符。 */
    private static final String SUPERSCRIPTS = "⁰¹²³⁴⁵⁶⁷⁸⁹";

    /** 度数符号的两种 OCR 写法：{@code ℃}（U+2103）与重复的 {@code °℃}，都还原成 {@code °C}。 */
    private static final Pattern DEGREE = Pattern.compile("°\\s*℃|℃");

    /** 参数名末端的裸上标脚注号（{@code Max. Speed⁴}、{@code Axial Runout⁵}）。 */
    private static final Pattern TRAILING_SUPERSCRIPT = Pattern.compile("[⁰¹²³⁴⁵⁶⁷⁸⁹]{1,2}$");

    /** 文本归一：度数符号统一写法。参数名与单位都要过一遍。 */
    static String normalizeText(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        return DEGREE.matcher(text).replaceAll("°C").strip();
    }

    /**
     * 参数名里的脚注标记：{@code [1]}、{@code (2)}、{@code （3）}、{@code [1,2]}、{@code [³]}。
     *
     * <p>样册把脚注号排成上标，PDF 文字层里可能是普通的 {@code [1]}，也可能是
     * 上标字符 {@code [³]}；OCR 后中英文括号都可能出现。
     */
    private static final Pattern NAME_MARKER = Pattern.compile(
            "[\\[\\(（]\\s*[0-9⁰¹²³⁴⁵⁶⁷⁸⁹]{1,2}"
                    + "(?:\\s*[,，]\\s*[0-9⁰¹²³⁴⁵⁶⁷⁸⁹]{1,2})*\\s*[\\]\\)）]");

    /** 「参数名在图上、文字层没取到」时的占位名（不能留空，见 {@link #splitNameMarkers}）。 */
    static final String NAMELESS = "（未识别）";

    /**
     * 剥离参数名里的脚注标记，返回 {@code [干净参数名, 编号串]}。
     *
     * <p>整格只剩标记（{@code [1]}）时给「（未识别）」占位，而不是留空 —— 留空会被
     * {@link #attachContinuations} 当成上一行的续行，把一个独立参数的值
     * （图纸里 {@code Continuous Current 6.0}）粘到邻行（{@code Te 6.90}）上去，
     * 变成「6.90；6.0」，比留个占位符难查得多。
     */
    static String[] splitNameMarkers(String name) {
        if (name == null || name.isEmpty()) {
            return new String[]{"", ""};
        }
        String source = normalizeText(name);
        List<String> marks = new ArrayList<>();
        StringBuilder clean = new StringBuilder();
        Matcher m = NAME_MARKER.matcher(source);
        int last = 0;
        while (m.find()) {
            clean.append(source, last, m.start());
            marks.add(digitsOf(m.group()));
            last = m.end();
        }
        clean.append(source.substring(last));
        String text = clean.toString().replaceAll("\\s+", " ").strip();

        // 括号外的裸上标：`Max. Speed⁴` 的 4 也是脚注号
        Matcher tail = TRAILING_SUPERSCRIPT.matcher(text);
        if (tail.find()) {
            marks.add(digitsOf(tail.group()));
            text = text.substring(0, tail.start()).strip();
        }

        if (text.isEmpty()) {
            return new String[]{marks.isEmpty() ? "" : NAMELESS, String.join(",", marks)};
        }
        return new String[]{rstrip(text, " -—/"), String.join(",", marks)};
    }

    /** 取字符串里的数字（上标数字先还原），多个编号用逗号连。 */
    private static String digitsOf(String raw) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            int sup = SUPERSCRIPTS.indexOf(c);
            if (sup >= 0) {
                sb.append((char) ('0' + sup));
            } else if (Character.isDigit(c)) {
                sb.append(c);
            } else if ((c == ',' || c == '，') && sb.length() > 0) {
                sb.append(',');
            }
        }
        return sb.toString();
    }

    /**
     * 记录「参数表是位图」的页：位图铺满页面（≥ {@link LayoutRules#IMAGE_PAGE_COVERAGE}），
     * 且页面文字里出现 SPECIFICATION / 规格 / 参数表 之类的标题。
     *
     * <p>只靠图片面积不够——样册里的整页曲线图、图纸也满足面积条件；标题关键词把
     * 「本该有参数表、但表被贴成了图片」这一类挑出来，才值得提示用户。
     */
    private void markImageTablePage(List<Integer> target, int pno, PdfPageScanner.PageScan scan) {
        if (isImageSpecPage(scan)) {
            target.add(pno);
        }
    }

    /**
     * 该页是否「位图为主 + 带参数表标题」，也就是值得跑 OCR 的页。
     *
     * <p>两种情况：
     * <ul>
     *   <li>位图铺满页面，且文字层里出现 SPECIFICATION / 规格 / 参数表 之类的标题
     *       —— 典型是「文字版图纸里把参数表贴成了图片」；</li>
     *   <li>位图铺满页面，且整页几乎没有任何文字（纯扫描页）—— 没有文字层可丢，
     *       OCR 是唯一出路，交给表头校验来兜底。</li>
     * </ul>
     * 只靠图片面积不够：样册里的整页曲线图、尺寸图也满足面积条件。
     */
    private boolean isImageSpecPage(PdfPageScanner.PageScan scan) {
        if (scan.imageCoverage() < LayoutRules.IMAGE_PAGE_COVERAGE) {
            return false;
        }
        StringBuilder text = new StringBuilder();
        for (TextRun r : scan.runs()) {
            text.append(r.text());            // 不加分隔符：标题可能被拆成多个 run
        }
        if (text.toString().trim().length() <= 3) {
            return true;                      // 纯扫描页：一个字都没有，没有文字层可丢，直接试
        }
        return LayoutRules.SPEC_TITLE_PATTERN.matcher(text.toString()).find();
    }

    /**
     * 渲染该页并 OCR，把结果换算成 PDF 点坐标的 {@link TextRun}。
     *
     * <p>坐标系：OCR 给的是像素（左上原点），页面逻辑坐标是 pt（左上原点），
     * 故像素 × 72 / dpi 即为 pt；基线取文字框底边，字号按字高 × 0.72 估算。
     */
    private List<TextRun> ocrRuns(PDDocument doc, int pageNumber) {
        if (ocrEngine == null || !ocrEngine.available()) {
            return List.of();
        }
        try {
            BufferedImage image = new PDFRenderer(doc)
                    .renderImageWithDPI(pageNumber - 1, LayoutRules.OCR_DPI, ImageType.RGB);
            double toPt = 72.0 / LayoutRules.OCR_DPI;
            // 小于 10px 的框基本是表格线/噪点，交给识别只会产生乱码
            List<OcrTextLine> lines = ocrEngine.recognize(image, 10);
            if (lines.isEmpty()) {
                return List.of();
            }
            List<TextRun> runs = new ArrayList<>(lines.size());
            for (OcrTextLine line : lines) {
                double size = Math.max(3.0, line.height() * toPt * OCR_CAP_RATIO);
                // 用框中心而不是底边：不同字母（y/g/p 有下伸部）会让底边相差 1~2pt，
                // 超过 BASELINE_TOLERANCE 后同一行会被拆开，表头的 Symbol 与 Unit 就凑不到一起。
                double baseline = (line.y0() + line.height() / 2.0) * toPt;
                runs.add(TextRun.of(line.text(), line.x0() * toPt, line.x1() * toPt,
                        baseline, Math.round(size * 10) / 10.0));
            }
            log.info("第 {} 页文字层无参数表，OCR 识别出 {} 行", pageNumber, runs.size());
            return runs;
        } catch (IOException | RuntimeException ex) {
            log.warn("第 {} 页 OCR 失败：{}", pageNumber, ex.toString());
            return List.of();
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
            // 中文样册用「符号 / 单位」，客户图纸（含 OCR 出来的）用 Symbol / Unit
            if (hasToken(tokens, "符号", "symbol") && hasToken(tokens, "单位", "unit")) {
                out.add(i);
            } else if (hasToken(tokens, "单位", "unit")
                    && tokens.stream().anyMatch(LayoutRules.CONFIG_WORDS::contains)) {
                out.add(i);
            }
        }
        return out;
    }

    /**
     * 表格右边界。
     *
     * <p>一、表头行本身有色块（多数样册的分节色块从表头开始）→ 取覆盖表头行的色块最右缘。
     *
     * <p>二、表头行没有色块 → 退化为「表头<b>下方</b>、且左缘在表头范围内」的色块最右缘。
     * 这里必须取 max、且必须排除表头上方的色块：AUM5 那类宽表的分节色块只出现在
     * 「机械参数 / 其他信息」两行，而页眉那条装饰色块（横跨标题栏、右缘 461pt）比整张表
     * 窄一百多 pt —— 取 min 或把它算进来，都会把 461 之后的 S9/S10/S12 三列整段裁掉，
     * 表现为「这几个型号没采集到」。
     *
     * @return 右边界；没有任何可用色块时返回 0（由调用方按表头文字宽度估算）
     */
    static double tableRightEdge(List<FillBand> bands, double headerY, double headerMaxX) {
        double right = 0;
        for (FillBand b : bands) {
            if (b.y0() - 20 <= headerY && headerY <= b.y1() + 6) {
                right = Math.max(right, b.x1());
            }
        }
        if (right > 0) {
            return right;
        }
        return bands.stream()
                .filter(b -> b.y0() >= headerY - 2)          // 表头上方的页眉/标题色块不算
                .filter(b -> b.x0() <= headerMaxX)           // 表外并排的色块（侧栏、下图）不算
                .mapToDouble(FillBand::x1).max().orElse(0);
    }

    /** 表头相邻列的中心距（取中位数），用于无底色块时推断「值列」在表头右侧多远。 */
    private static double columnStep(TextLine header) {
        List<Double> cxs = header.runs().stream().map(TextRun::cx).sorted().toList();
        if (cxs.size() < 2) {
            return 60.0;
        }
        List<Double> gaps = new ArrayList<>();
        for (int i = 1; i < cxs.size(); i++) {
            gaps.add(cxs.get(i) - cxs.get(i - 1));
        }
        gaps.sort(Double::compare);
        return Math.max(20.0, gaps.get(gaps.size() / 2));
    }

    /** 表头 token 匹配（忽略大小写，用于兼容 Symbol/Unit 与 符号/单位）。 */
    private static boolean hasToken(java.util.Collection<String> tokens, String... names) {
        for (String t : tokens) {
            for (String n : names) {
                if (t.equalsIgnoreCase(n)) {
                    return true;
                }
            }
        }
        return false;
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

        /**
         * 文字该归哪一列：**优先看左缘**，中心点只作兜底。
         *
         * <p>为什么不能只看中心：宽单位（{@code Vpeak/(m/s)}、{@code N/Sqrt(W)}）的中心会越到
         * 值列去，整段单位就被搬进「值」，变成 {@code Vpeak/(m/s)356}——单位丢失、数值被污染。
         *
         * <p>为什么左缘还要修正：OCR 的检测框会整体左偏零点几 pt，符号 {@code Fcn} 的左缘
         * 落进参数名列最后 0.6pt 里，按左缘硬判就整行串位。所以「左缘贴近本列右边界」
         * 时再看一眼——只有**文字主体大半已经在下一列**才算下一列的内容；右对齐的短数值
         * （{@code 磁吸力} 的 {@code 0}）左缘离右边界也近，但它一点都没越界，不能被推走。
         */
        String of(TextRun run) {
            double x0 = run.x0();
            double width = Math.max(0.1, run.x1() - x0);
            for (int i = 0; i < columns.size(); i++) {
                Column c = columns.get(i);
                if (c.lo() <= x0 && x0 < c.hi()) {
                    if (i + 1 < columns.size() && x0 > c.hi() - LayoutRules.COLUMN_EDGE_TOLERANCE) {
                        Column next = columns.get(i + 1);
                        double insideNext = Math.min(run.x1(), next.hi()) - Math.max(x0, next.lo());
                        if (insideNext >= width * 0.5) {
                            return next.name();
                        }
                    }
                    return c.name();
                }
            }
            return at(run.cx());
        }

        List<String> configTexts() {
            return configs.stream().map(TextRun::token).toList();
        }
    }

    /** 由表头 token 的中心线推导列边界。 */
    /** 按表头文字取列锚点（中文/英文写法都认）。 */
    private static TextRun pickRun(Map<String, TextRun> byText, String... names) {
        for (String name : names) {
            for (Map.Entry<String, TextRun> e : byText.entrySet()) {
                if (e.getKey().equalsIgnoreCase(name)) {
                    return e.getValue();
                }
            }
        }
        return null;
    }

    Columns buildColumns(TextLine header, double rightEdge) {
        return buildColumns(header, rightEdge, false);
    }

    /**
     * @param tightUnitColumn 单位列右界取「单位右缘与值列左缘的中点」。
     *                         仅用于 OCR 页：图纸的数值与表头 x 几乎重合，
     *                         按中心线切会把数值判进单位列；样册用的是中心线口径，不能改（会破坏型号分组）。
     */
    Columns buildColumns(TextLine header, double rightEdge, boolean tightUnitColumn) {
        Map<String, TextRun> byText = new LinkedHashMap<>();
        for (TextRun r : header.runs()) {
            byText.putIfAbsent(r.token(), r);
        }
        TextRun sym = pickRun(byText, "符号", "symbol");
        TextRun unit = pickRun(byText, "单位", "unit");
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
        double firstCfgLeft;
        if (tightUnitColumn && unit != null) {
            // OCR 页一律用「单位右缘」与「第一值列左缘」的中点，多列也一样：
            // 渲染图里表头列距并不均匀（Unit→B 比 B→D 窄得多），按等间距外推会把边界
            // 推到单位文字中间——短单位（N、Ω）有一半越过边界，就被「贴边跳列」推进值列，
            // 变成 N3489 / Ω3.0（AKM200-A-B4-W 图纸）。单列时同样成立：表头文字常与
            // 右对齐的数值重叠（图纸里 Parallel 与 15 的 x 几乎一致），按中心线切会把数值判进单位列。
            firstCfgLeft = (unit.x1() + configs.get(0).x0()) / 2;
        } else if (configs.size() > 1) {
            firstCfgLeft = configs.get(0).cx() - (configs.get(1).cx() - configs.get(0).cx()) / 2;
        } else {
            firstCfgLeft = configs.get(0).cx() - 10;
        }
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

    /** 型号名的续行/后缀：{@code -V107}、{@code -B4} 这类单独成行或单独成 token 的部分。 */
    private static final Pattern MODEL_SUFFIX = Pattern.compile("^[-/][A-Za-z0-9.]+$");

    /**
     * 表头上方的型号名（可能横跨 2~3 行），按 x 排序。
     *
     * <p>宽表会把型号名排成两行：左边 S1~S6 与右边 S8~S12 的字号/基线并不一致
     * （AUM5 那页右边整块上移 4pt，超过 {@link LayoutRules#LINE_TOLERANCE}，被拆成两条「行」），
     * 再加上「AUM5-S8 / -V107」这种型号与后缀分行的写法 —— 只取「表头上方一行」必然只拿到半边，
     * 另外半边就成了没有型号归属的裸列（导出时列名退化成「串联/并联」，型号只剩 AUM5-S1）。
     *
     * <p>扫描上限 3 行，遇到含其他文字的行走人（表标题、上一张表的表体）。
     */
    List<TextRun> modelNames(List<TextLine> lines, int headerIndex) {
        List<TextRun> models = new ArrayList<>();
        List<TextRun> suffixes = new ArrayList<>();
        int scanned = 0;
        for (int i = headerIndex - 1; i >= 0 && scanned < 3; i--, scanned++) {
            boolean nameRow = true;
            for (TextRun r : lines.get(i).runs()) {
                String t = r.token();
                if (t.isEmpty()) {
                    continue;
                }
                if (LayoutRules.MODEL_PATTERN.matcher(t).matches()) {
                    models.add(r);
                } else if (MODEL_SUFFIX.matcher(t).matches()) {
                    suffixes.add(r);
                } else {
                    nameRow = false;
                    break;
                }
            }
            if (!nameRow) {
                break;
            }
        }
        if (models.isEmpty()) {
            return List.of();
        }
        // 后缀（-V107 / -V80）按横向重叠贴回型号：AUM5-S8 + -V107 → AUM5-S8-V107
        for (TextRun s : suffixes) {
            TextRun host = null;
            double best = 0;
            for (TextRun m : models) {
                double ov = Math.min(m.x1(), s.x1()) - Math.max(m.x0(), s.x0());
                if (ov > best) {
                    best = ov;
                    host = m;
                }
            }
            if (host != null) {
                int idx = models.indexOf(host);
                models.set(idx, TextRun.of(host.token() + s.token(), Math.min(host.x0(), s.x0()),
                        Math.max(host.x1(), s.x1()), host.oy(), host.size()));
            }
        }
        models.sort(Comparator.comparingDouble(TextRun::x0));
        return models;
    }

    /**
     * 表头上方的型号名 → 各自占哪几列（列下标集合）。
     *
     * <p>按「型号名与列区间的横向重叠最大者」判归属，而不是「列中心落在型号名范围里」：
     * 型号名的宽度是排版者随手定的，和列宽并不同步（AUM5-S6 的名字右缘距下一列中心只差 0.05pt，
     * 旧判据会把 S8 的 P5 列算给 S6）。重叠量还顺带解决了「一个型号占 1 列还是 2 列」。
     */
    Map<String, List<Integer>> modelGroups(List<TextLine> lines, int headerIndex, Columns cols) {
        List<TextRun> names = modelNames(lines, headerIndex);
        if (names.size() < 2) {
            return Map.of();
        }
        Map<String, List<Integer>> groups = new LinkedHashMap<>();
        for (int i = 0; i < cols.columns().size(); i++) {
            Column c = cols.columns().get(i);
            if (!c.name().startsWith("v")) {
                continue;
            }
            double width = Math.max(1.0, c.hi() - c.lo());
            TextRun owner = null;
            double best = 0;
            for (TextRun s : names) {
                // 允许 2pt 的边界误差：型号名的字面宽度常比它覆盖的列窄一点点
                double ov = Math.min(c.hi(), s.x1() + 2) - Math.max(c.lo(), s.x0() - 2);
                if (ov > best) {
                    best = ov;
                    owner = s;
                }
            }
            if (owner != null && best >= width * 0.2) {
                groups.computeIfAbsent(owner.token(), k -> new ArrayList<>()).add(i);
            }
        }
        // 一个型号都没占到的（表标题里单独的 AUM5 之类）不算型号列
        groups.entrySet().removeIf(e -> e.getValue().isEmpty());
        return groups;
    }

    /**
     * 版次/草案标注：图纸的表标题常写成 {@code CRM0013-Preliminary}、{@code AXD120-初步}，
     * 那是「本表为初版」的说明，不是型号的一部分。
     */
    private static final Pattern DRAFT_WORD = Pattern.compile(
            "(?i)(?:^|[-_\\s])(?:preliminary|prelim|draft|tentative|provisional)(?![a-z])"
                    + "|初步|初版|草案|仅供参考");

    /** 型号名去掉版次词；整串都是版次词时原样返回（不做无根据的猜测）。 */
    static String cleanModel(String model) {
        if (model == null || model.isEmpty()) {
            return "";
        }
        String s = DRAFT_WORD.matcher(model).replaceAll(" ").replaceAll("\\s+", " ").strip();
        s = rstrip(s, "-_/ ");
        return s.isEmpty() ? model : s;
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
            return cleanModel(pref.isEmpty() ? names.get(0) : pref);
        }
        for (int i = headerIndex - 1; i >= 0; i--) {
            for (TextRun s : lines.get(i).runs()) {
                if (LayoutRules.MODEL_PATTERN.matcher(s.token()).matches()) {
                    return cleanModel(s.token());
                }
            }
        }
        return "未命名型号";
    }

    List<String> columnLabels(Columns cols, Map<String, List<Integer>> groups) {
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
            // 型号名里已经含有这个接法/绕组代号时不再重复拼：AUM6 的型号本身就写成
            // AUM6-P5-S4，表头再标一次 P5，会拼出 AUM6-P5-S4-P5 这种不存在的型号。
            if (owner != null && !token.isEmpty() && hasSegment(owner, token)) {
                labels.add(owner);
                continue;
            }
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

    /** 型号里是否已有 {@code -token-} 这样的一段（按 {@code -} 分段、忽略大小写）。 */
    private static boolean hasSegment(String model, String token) {
        for (String part : model.split("-")) {
            if (part.equalsIgnoreCase(token)) {
                return true;
            }
        }
        return false;
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
        return parseBlock(lines, headerIndex, cols, LayoutRules.BLOCK_LINE_GAP);
    }

    Block parseBlock(List<TextLine> lines, int headerIndex, Columns cols, double lineGap) {
        int end = lines.size();
        for (int j = headerIndex + 1; j < lines.size(); j++) {
            if (isFootnoteStart(lines.get(j))
                    || lines.get(j).oy() - lines.get(j - 1).oy() > lineGap) {
                end = j;
                break;
            }
        }

        List<TableRow> rows = new ArrayList<>();
        for (int j = headerIndex + 1; j < end; j++) {
            TextLine ln = lines.get(j);
            Map<String, List<TextRun>> cells = new LinkedHashMap<>();
            for (TextRun r : ln.runs()) {
                String c = cols.of(r);
                if (c == null) {
                    continue;
                }
                cells.computeIfAbsent(c, k -> new ArrayList<>()).add(r);
            }
            Map<String, CellText> rendered = new LinkedHashMap<>();
            for (Map.Entry<String, List<TextRun>> e : cells.entrySet()) {
                rendered.put(e.getKey(), joinCell(e.getKey(), e.getValue()));
            }

            TableRow row = new TableRow();
            row.oy = ln.oy();
            // 参数名里夹带的脚注标记（`@100C[1]`）要剥出来单独存：留在名字里就永远匹配不上
            // 字段字典。剥完若只剩占位符，说明名字在图上、文字层没取到。
            String[] nameAndMark = splitNameMarkers(text(rendered, "name"));
            row.name = nameAndMark[0];
            row.sym = text(rendered, "sym");
            row.unit = normalizeUnit(normalizeText(text(rendered, "unit")));
            for (Map.Entry<String, CellText> e : rendered.entrySet()) {
                if (e.getKey().startsWith("v") && !e.getValue().text().isEmpty()) {
                    row.values.put(e.getKey(), e.getValue().text());
                }
            }
            StringBuilder foot = new StringBuilder(nameAndMark[1]);
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

    /**
     * 单元格内多行文字用「；」连接，小字号纯数字抽成脚注编号。
     *
     * @param key 列键（{@code name}/{@code sym}/{@code unit}/{@code v0}…）：<b>符号列不抽脚注</b>，
     *            因为符号的下标和脚注号长得一样（见方法内的注释）
     */
    CellText joinCell(String key, List<TextRun> spans) {
        boolean symColumn = "sym".equals(key);
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
            // 脚注号的第二个必要条件：**紧贴在前一段正文后面**。
            // 只按「小字号 + 1~2 位数字」判，会把同样小字号的参数值一起吞掉：
            // AUM3 的参数表用 5pt 排数值，{@code 持续推力 28N}、{@code 电磁周期 60}、
            // {@code 磁吸力 0} 都被当成脚注编号抽进 footnote，值列变空，入库还报
            // 「缺少必填参数：持续推力」。数值单元格只有它自己一个 run（prev = null），
            // 因此永远不会被误判；真脚注号（{@code @100°C} 后面跟的 {@code 1}）间距约 1pt。
            TextRun prevAny = null;
            TextRun prevBody = null;
            boolean prevWasFoot = false;
            for (TextRun s : line) {
                boolean pure = PURE_NUMBER.matcher(s.token()).matches();
                boolean adjacent = prevAny != null
                        && s.x0() - prevAny.x1() <= LayoutRules.FOOTNOTE_ATTACH_GAP;
                // 前一段是正文（含字母/中文）才算脚注号的起点；连续两个编号（`1 4`）也允许，
                // 但不能是「两个数值挨在一起」—— 那种情况是参数值，不是脚注。
                boolean afterBody = prevBody != null && !PURE_NUMBER.matcher(prevBody.token()).matches();
                // 符号列不抽脚注号：`R25` 的 `25` 与真脚注号同为「小字号纯数字 + 紧贴」，
                // 唯一稳定的差别是<b>列</b>——脚注号跟在参数名后面（名字列），下标长在符号里面。
                // 不区分开，符号就只剩 `R`，入库时报「缺少必填参数：相间电阻」（ADR050-T-014）。
                boolean foot = !symColumn
                        && adjacent
                        && s.size() <= LayoutRules.FOOTNOTE_MAX_SIZE
                        && FOOTNOTE_NO.matcher(s.token()).matches()
                        && (prevWasFoot || afterBody);
                if (foot) {
                    marks.append(s.token());
                } else {
                    buf.append(s.text());
                    prevBody = s;
                }
                prevAny = s;
                prevWasFoot = foot;
            }
            String t = buf.toString().strip();
            // 名字格里只剩一串小字号数字：那是上标脚注号单独落到了一条基线上（不是参数名）。
            // AUM5 的「热耗散常数(气冷)¹ / (水冷)⁴」两个编号被排在同一基线上，整行会被读成
            // 参数名「14；热耗散常数(水冷)」，字段字典再也匹配不上。
            if (!t.isEmpty() && "name".equals(key) && PURE_NUMBER.matcher(t).matches()
                    && line.stream().allMatch(s -> PURE_NUMBER.matcher(s.token()).matches())) {
                marks.append(t);
                t = "";
            }
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
                boolean closer = diff < bestDiff - LayoutRules.CONTINUATION_TIE;
                // 距离打平时归上方：参数名被拆成上下两截、值居中时，两截的距离几乎相等，
                // 而参数名的第一行才是「这行参数」的锚点
                boolean tieAbove = diff <= bestDiff + LayoutRules.CONTINUATION_TIE
                        && target != null && n.oy < target.oy;
                if (target == null || closer || tieAbove) {
                    bestDiff = diff;
                    target = n;
                }
            }
            if (target == null || bestDiff > LayoutRules.CONTINUATION_ATTACH) {
                continue;
            }

            // 同一物理行被 OCR 拆成两条基线时（符号的字身矮，常单独成一条），
            // 缺的那几格应当「补空」，而不是拼成一串字符串。
            // 不然 `Electrical Time Constant | Te | ms | 0.35` 会变成
            // 「单位 = ms0.35；Te」，整行数据作废。
            if (!r.sym.isEmpty()) {
                // 一行只可能有一个符号：取更长的那个，`1` 这种单字符碎片
                // （OCR 把 Icn 从中间切开）让给真正的符号
                if (r.sym.length() > target.sym.length()) {
                    target.sym = r.sym;
                }
                r.sym = "";
            }
            if (target.unit.isEmpty() && !r.unit.isEmpty()) {
                target.unit = r.unit;
                r.unit = "";
            }
            if (target.values.isEmpty() && !r.values.isEmpty()) {
                target.values.putAll(r.values);
                r.values.clear();
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
                // base 的构造是「单位 + 值」，多值拼接时单位会混进第一个值里。
                // 单位列本来就有内容、且值确实以它开头时，剥掉重复的那截、把单位留在单位列；
                // 否则（单位来自别的行）才退回「整串塞进值」。
                if (!target.unit.isEmpty() && parts.get(0).startsWith(target.unit)) {
                    parts.set(0, parts.get(0).substring(target.unit.length()));
                } else {
                    target.unit = "";
                }
                target.values.clear();
                target.values.put("v0", String.join("；", parts).strip());
                target.merged = true;
            }
        }
        return keep;
    }

    /**
     * 参数名单元格纵向合并时，名字占两行、数值居中停在两行之间，
     * OCR 给出的就是「名字 / 值 / 名字」三行 —— 下半截会变成一条参数名叫
     * {@code @100C} 的怪行。这里把下半截接回上一行名字后面。
     *
     * <p>三重保险，只在三条同时成立时才合并：①该行只有名字，符号/单位/值全空
     * （独立参数行不可能三者全空，分节行已被 {@link #markSections} 标成 section 而排除）；
     * ②名字以 {@code (}、{@code @}、{@code [}、数字或小写字母开头（分节名不会）；
     * ③与相邻有值行的距离不超过 1.6 倍行距（就是紧挨着，不是下一段）。
     */
    List<TableRow> mergeWrappedNames(List<TableRow> rows) {
        double pitch = medianPitch(rows);
        List<TableRow> out = new ArrayList<>();
        for (int i = 0; i < rows.size(); i++) {
            TableRow r = rows.get(i);
            if (isWrappedName(r)) {
                TableRow above = i > 0 ? rows.get(i - 1) : null;
                TableRow below = i + 1 < rows.size() ? rows.get(i + 1) : null;
                TableRow host = null;
                if (above != null && hasContent(above) && adjacent(r, above, pitch)) {
                    host = above;
                } else if (below != null && hasContent(below) && adjacent(r, below, pitch)) {
                    host = below;
                }
                if (host != null) {
                    host.name = host.oy < r.oy ? joinName(host.name, r.name) : joinName(r.name, host.name);
                    continue;
                }
            }
            out.add(r);
        }
        return out;
    }

    /** 名字续行拼接：两侧都缺分隔时补一个空格（{@code …(NC} + {@code @100C} → {@code …(NC @100C}）。 */
    private static String joinName(String first, String second) {
        if (first.isEmpty()) {
            return second;
        }
        if (second.isEmpty()) {
            return first;
        }
        char left = first.charAt(first.length() - 1);
        char right = second.charAt(0);
        boolean tight = Character.isWhitespace(left) || Character.isWhitespace(right)
                || left == '(' || left == '（' || left == '/'
                || right == ')' || right == '）' || right == ',' || right == '，' || right == '/';
        return tight ? first + second : first + " " + second;
    }

    /** 只有名字、其余三列全空的行（分节行不算：它已经被 {@link #markSections} 标过）。 */
    private static boolean isWrappedName(TableRow r) {
        return TableRow.KIND_ROW.equals(r.kind)
                && r.name != null && !r.name.isEmpty()
                && r.sym.isEmpty() && r.unit.isEmpty() && r.values.isEmpty()
                && LayoutRules.WRAPPED_NAME_START.matcher(r.name).matches();
    }

    private static boolean hasContent(TableRow r) {
        return TableRow.KIND_ROW.equals(r.kind) && !r.values.isEmpty();
    }

    private static boolean adjacent(TableRow a, TableRow b, double pitch) {
        return Math.abs(a.oy - b.oy) <= pitch * 1.6;
    }

    /** 本块行距的中位数：用它判断「紧挨着」比写死 pt 更抗版式差异（图纸 21pt、样册 15pt）。 */
    private static double medianPitch(List<TableRow> rows) {
        List<Double> gaps = new ArrayList<>();
        for (int i = 1; i < rows.size(); i++) {
            double gap = rows.get(i).oy - rows.get(i - 1).oy;
            if (gap > 0.5) {
                gaps.add(gap);
            }
        }
        if (gaps.isEmpty()) {
            return LayoutRules.BLOCK_LINE_GAP;
        }
        gaps.sort(Double::compare);
        return gaps.get(gaps.size() / 2);
    }

    /**
     * OCR 会把同一行的「参数名」和「数值」还原到两条基线上（字形高度不同，检测框被切开），
     * 于是出现「有名无值」+「无名有值」两行。这里按纵向就近把它们拼回一行。
     *
     * <p>只在无名行（{@link #NAMELESS}，即原格只剩脚注标记）存在时触发——文字层正常的表格
     * 不会走到这条路径。拼不上的无名行若只有横线类字符，整行没有信息，直接丢弃。
     */
    List<TableRow> reuniteBrokenRows(List<TableRow> rows) {
        List<TableRow> out = new ArrayList<>();
        for (TableRow r : rows) {
            if (!NAMELESS.equals(r.name)) {
                out.add(r);
                continue;
            }
            TableRow host = null;
            double best = Double.MAX_VALUE;
            for (TableRow a : rows) {
                if (a == r || a.name.isEmpty() || NAMELESS.equals(a.name) || !a.values.isEmpty()) {
                    continue;                          // 只往「有参数名、值还空着」的行上拼
                }
                double diff = Math.abs(a.oy - r.oy);
                if (diff < best) {
                    best = diff;
                    host = a;
                }
            }
            String only = String.join("", r.orderedValues()).strip();
            if (host != null && best <= LayoutRules.CONTINUATION_ATTACH && !isBlankValue(only)) {
                host.values.putAll(r.values);
                host.footnote = (host.footnote + r.footnote).strip();
                continue;
            }
            if (isBlankValue(only)) {
                continue;                              // 「—」这种占位值，丢了不影响
            }
            out.add(r);
        }
        return out;
    }

    /** 值是否形同虚设：空，或整串只有横线/斜线类字符（{@code -}、{@code —}、{@code /}）。 */
    static boolean isBlankValue(String value) {
        if (value == null || value.isBlank()) {
            return true;
        }
        for (int i = 0; i < value.length(); i++) {
            if ("-－—–_─一/".indexOf(value.charAt(i)) < 0) {
                return false;
            }
        }
        return true;
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
            // 底色块不是必需品：位图 OCR 出来的表没有矢量色块，只能靠分节标题的命名形态判断
            if ((inBand && nameOnly) || (nameOnly && LayoutRules.isSectionName(r.name))) {
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
                keep = LayoutRules.isKeptSection(current);
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

    /**
     * 按采集口径剔除个别参数行（2026-11-19 用户裁定：「最高线圈温度」不采集）。
     *
     * <p>与分节过滤分开两步：分节白名单决定「哪些分节要」，这一步决定「保留的分节里
     * 哪些项不要」。判定规则集中在 {@link LayoutRules#isExcludedParameter}，这里只负责
     * 删行并把「参数名 → 删了几行」记进 {@code excluded}，供识别报告追溯。
     */
    List<TableRow> dropExcludedParameters(List<TableRow> rows, Map<String, Integer> excluded) {
        List<TableRow> out = new ArrayList<>();
        for (TableRow r : rows) {
            if (!r.isSection() && LayoutRules.isExcludedParameter(r.name, r.sym)) {
                String key = r.name == null || r.name.isBlank() ? NAMELESS : r.name.strip();
                if (excluded != null) {
                    excluded.merge(key, 1, Integer::sum);
                }
                continue;
            }
            out.add(r);
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

    /** 去重保序的输出型号列表（写 Excel 分页用；冷却变体算独立型号，带 -A / -W 后缀）。 */
    static List<String> distinctModels(List<CatalogTable> tables) {
        LinkedHashSet<String> set = new LinkedHashSet<>();
        for (CatalogTable t : tables) {
            set.add(t.outputModel());
        }
        return List.copyOf(set);
    }
}
