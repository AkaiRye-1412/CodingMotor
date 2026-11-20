package com.motorsizer.app.importing.render;

import java.util.Set;
import java.util.regex.Pattern;

/**
 * 版面解析的全部数值口径（唯一来源）。
 *
 * <p>数值与 pdf2excel.py 顶部常量一一对应，改这里即可调整识别灵敏度；
 * 测试用真实样册回归时不要随手改，先看 {@code CatalogParserTest} 的期望。
 */
public final class LayoutRules {

    private LayoutRules() {
    }

    /** 左侧装饰性竖排文字（样册竖排产品名）的右界，x0 小于它的文字不参与表格解析。 */
    public static final double X_MIN = 35.0;

    /** 接法列关键词。 */
    public static final Set<String> CONFIG_WORDS = Set.of("串联", "并联", "单列", "双列");

    /** 分节行底色（样册浅蓝），按 RGB 容差匹配。 */
    public static final int SECTION_FILL_RGB = 0xCCE1F5;
    public static final int SECTION_FILL_TOLERANCE = 16;

    /** 分节色块的几何约束：宽 ≥ 150pt、高 3~22pt、左缘 ≤ 90pt。 */
    public static final double BAND_MIN_WIDTH = 150.0;
    public static final double BAND_MIN_HEIGHT = 3.0;
    public static final double BAND_MAX_HEIGHT = 22.0;
    public static final double BAND_MAX_X0 = 90.0;

    /** 脚注上标字号上限：小于它的纯数字视为脚注编号。 */
    public static final double FOOTNOTE_MAX_SIZE = 4.3;

    /**
     * 脚注编号与正文的最大间距：编号必须**紧贴在前一段文字后面**才算脚注。
     *
     * <p>只按「小字号 + 1~2 位数字」判会把参数值一并吞掉：AUM3 的参数表用 5pt 排数值，
     * 而尺子一量它和脚注号一样小 —— 于是 {@code 持续推力 28N}、{@code 电磁周期 60}、
     * {@code 磁吸力 0} 全被当成脚注编号抽进 footnote 字段，值列变空，
     * 入库时还报「缺少必填参数：持续推力」。样册的脚注号与正文间距约 1pt。
     */
    public static final double FOOTNOTE_ATTACH_GAP = 3.0;

    /** 型号名形态（AXD120-71 / DD-30 / AQM30-3.4 等）。 */
    public static final Pattern MODEL_PATTERN =
            Pattern.compile("^[A-Z]{2,6}\\d{1,4}[A-Za-z]?(?:[-/][A-Za-z0-9.]+)*$");

    // ---------------------------------------------------------------- 聚类容差

    /** 同一基线的 y 容差（字形聚成文本行之前的一级聚类）。 */
    public static final double BASELINE_TOLERANCE = 0.8;

    /** 文本 run 内部的水平间距容差：字号 × 系数，并夹在 [min, max] 之间。 */
    public static final double RUN_GAP_FACTOR = 0.33;
    public static final double RUN_GAP_MIN = 1.0;
    public static final double RUN_GAP_MAX = 6.0;

    /** 行聚类：主字号行的 y 容差。 */
    public static final double LINE_TOLERANCE = 2.6;
    /** 行聚类：小于该字号的 span 视为下标/上标。 */
    public static final double SUBSCRIPT_SIZE = 4.4;
    /** 行聚类：下标/上标吸附到主行的最大距离。 */
    public static final double SUBSCRIPT_ATTACH = 5.2;

    /** 表块内的换行判定：行距超过它即认为表格结束。 */
    public static final double BLOCK_LINE_GAP = 17.0;

    /**
     * OCR 页的表块换行阈值。
     *
     * <p>图纸的行距比样册大（300dpi 下约 75px ≈ 18pt，样册是 15pt 上下），
     * 沿用 17pt 会在第一行数据处就判定「表格结束」，整页解析出 0 行。
     */
    public static final double BLOCK_LINE_GAP_OCR = 26.0;

    /** 单元格内多行文字的 y 容差（合并单元格的第二三行）。 */
    public static final double CELL_LINE_TOLERANCE = 3.0;

    /**
     * 列归属：文字左缘贴到本列右边界这么近时，判为下一列的内容。
     *
     * <p>OCR 检测框会整体左偏零点几 pt，符号 {@code Fcn} 的左缘会落进参数名列最后 0.6pt；
     * 按左缘硬判就会「名字 + 符号 + 单位 + 值」串成一格。给个容差把它推回下一列。
     */
    public static final double COLUMN_EDGE_TOLERANCE = 6.0;

    /** 续行归属：与有名行的最大 y 距离。 */
    public static final double CONTINUATION_ATTACH = 11.0;

    /**
     * 续行归属的「平分距离」：两个候选行与续行的距离相差不多时，归上方那一个。
     *
     * <p>参数名的单元格常常纵向合并成两行、数值居中停在两行之间，于是「名字 / 值 / 名字」
     * 三条基线两两距离几乎相等。参数名的第一行才是这一行参数的锚点，归下方会把下半截
     * 名字变成一条独立参数（图纸里出现过参数名叫 {@code @100C} 的行）。
     */
    public static final double CONTINUATION_TIE = 3.0;

    /** 名字续行的开头：{@code (NC}、{@code @100°C}、{@code [1]}、{@code /min}；分节名不会这样开头。 */
    public static final java.util.regex.Pattern WRAPPED_NAME_START = java.util.regex.Pattern.compile(
            "^[（(@\\[0-9a-z].*");

    /** 其他信息里的纵向合并标签：上下 10.5pt 内的行归为子项。 */
    public static final double LABEL_ATTACH = 10.5;

    /** 表块至少要有几行「有参数名」才算识别成功（过滤公告/目录页）。 */
    public static final int MIN_NAMED_ROWS = 4;

    /**
     * 磁轨规格小表的标题词。
     *
     * <p>ACR 弧形电机页面上，主参数表<b>右侧另画一张小表</b>「磁轨规格 / ACR240-TR36」，
     * 只有「角度 / 质量 / 转动惯量」三行。它整块落在主表右边界之外（实测主表 right≈232，
     * 小表从 x≈247 起），通用表格算法按右边界裁剪时把它整段裁掉了，所以单独按坐标取。
     */
    public static final String TRACK_SPEC_TITLE = "磁轨规格";

    /** 磁轨型号的中缀：{@code ACR335-TR36} 中 {@code -TR} 之前就是电机型号前缀。 */
    public static final String TRACK_MODEL_INFIX = "-TR";

    /** 以「转动惯量」行为锚点，向上找磁轨型号的纵向跨度（pt）。 */
    public static final double TRACK_SPEC_SPAN = 40.0;

    /**
     * 只保留这些分节（2026-11-19 用户裁定：只抓性能参数；机械参数/编码器参数/其他信息一律丢弃）。
     *
     * <p>白名单而非黑名单：样册里同一含义的分节写法不统一（「其他信息」/「其它信息」），
     * 名单外的分节一律不进 Excel，新增要保留的分节直接加到集合里。
     *
     * <p>英文条目用于客户图纸（OCR 出来的分节是 {@code Performance Parameters}）。
     */
    public static final Set<String> KEEP_SECTIONS = Set.of(
            "性能参数", "Performance Parameters", "Performance Parameter");

    /**
     * 采集口径下不采集的参数符号（2026-11-19 用户裁定：不需要「最高线圈温度」）。
     *
     * <p>符号按「只留字母数字、转小写」后比较，样册里的 {@code Tmax} / {@code tmax} /
     * {@code T max} 都归一到 {@code tmax}。
     */
    public static final Set<String> EXCLUDED_PARAM_SYMBOLS = Set.of("tmax");

    /**
     * 采集口径下不采集的参数名关键词（同样先做「只留字母数字/汉字、转小写」再匹配）。
     *
     * <p>符号列不总是可读（OCR 会把 {@code tmax} 读成 {@code tmax}/{@code 1max}），
     * 中文样册与英文图纸的参数名写法也不同，所以符号与名字双路判断：
     * 名字里带「线圈温度」「绕组温度」或 {@code coil temp} / {@code winding temp} 即排除。
     */
    public static final Set<String> EXCLUDED_PARAM_WORDS =
            Set.of("线圈温度", "绕组温度", "coiltemp", "windingtemp");

    /** 报告与提示语里的人话描述（与上面的名单同源，改名单时同步改这一句）。 */
    public static final String EXCLUDED_PARAM_DESC = "最高线圈温度（符号 Tmax）";

    /** 该参数行是否不采集（按名字与符号判断，任一命中即排除）。 */
    public static boolean isExcludedParameter(String name, String symbol) {
        String sym = keyword(symbol);
        if (!sym.isEmpty() && EXCLUDED_PARAM_SYMBOLS.contains(sym)) {
            return true;
        }
        String key = keyword(name);
        if (key.isEmpty()) {
            return false;
        }
        for (String word : EXCLUDED_PARAM_WORDS) {
            if (key.contains(word)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 采集口径下、机械参数分节里<b>额外保留</b>的参数名关键词（2026-11-20 用户裁定）。
     *
     * <p>背景：{@link #KEEP_SECTIONS} 只保留「性能参数」分节，而「质量」「转动惯量」都在
     * 「机械参数」分节里，整段被丢弃。用户要求直线/音圈电机采集「线圈（动子）质量」、
     * 力矩电机采集「转动惯量」——这两项从机械参数分节里单独捞出来，其余机械参数仍丢弃。
     *
     * <p>关键词必须写<b>完整词</b>，不能用宽泛的「质量」/「mass」：否则「总质量」「铁芯质量」
     * 「磁轨质量」（{@code total/core/track mass}）也会被误捞进来，而用户只要线圈/动子质量。
     * 与 {@link #EXCLUDED_PARAM_WORDS} 一样，按「只留字母数字/汉字、转小写」后包含匹配。
     */
    public static final Set<String> KEEP_EXTRA_PARAM_WORDS = Set.of(
            // 质量（moverMass）：直线 / 音圈共用的「线圈质量 / 动子质量」
            "线圈质量", "动子质量", "movermass", "movingmass", "forcermass",
            // 转动惯量（inertia）：力矩
            "转动惯量", "转子惯量", "momentofinertia", "inertia");

    /** 报告与提示语里的人话描述（与上面的名单同源，改名单时同步改这一句）。 */
    public static final String KEEP_EXTRA_PARAM_DESC = "线圈/动子质量、转动惯量（来自机械参数分节）";

    /**
     * 该参数行是否属于「机械参数分节里要额外采集」的项（质量 / 转动惯量）。
     *
     * <p>只按名字关键词判断（不做符号匹配）：模板里 {@code moverMass} / {@code inertia} 都没有
     * symbol，字段匹配本就靠名字别名；机械参数的符号（如转动惯量的 {@code J}）单字母太短，
     * 引入符号名单会误伤端跳/载荷等其他单字母符号。
     */
    public static boolean isKeptExtraParameter(String name, String symbol) {
        String key = keyword(name);
        if (key.isEmpty()) {
            return false;
        }
        for (String word : KEEP_EXTRA_PARAM_WORDS) {
            if (key.contains(word)) {
                return true;
            }
        }
        return false;
    }

    /** 比较用的归一形态：只留字母、数字与汉字，转小写（去掉空格、点、括号、上下标等）。 */
    static String keyword(String text) {
        if (text == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isLetterOrDigit(c) || (c >= '\u4e00' && c <= '\u9fff')) {
                sb.append(Character.toLowerCase(c));
            }
        }
        return sb.toString();
    }

    /**
     * 分节行名形态：用于「没有底色块」的表格（位图 OCR 出来的表）判断某行是分节标题而不是数据行。
     *
     * <p>只认明确的参数/信息类命名，避免把 {@code Motor Constant}、{@code Ambient Temperature}
     * 这类数据行误判成分节。
     */
    public static final Pattern SECTION_NAME_PATTERN = Pattern.compile(
            "(?i)^(?:performance|mechanical|electrical|environmental|encoder|general|other)"
                    + "(?:\\s+(?:parameters?|information|specifications?|data))?"
                    + "|^[\\u4e00-\\u9fff]{0,6}(?:参数|信息)$");

    /** 分节名是否在保留名单里（图纸/样册的大小写与写法不完全一致）。 */
    public static boolean isKeptSection(String name) {
        if (name == null) {
            return false;
        }
        String n = name.strip();
        for (String keep : KEEP_SECTIONS) {
            if (keep.equalsIgnoreCase(n)) {
                return true;
            }
        }
        return false;
    }

    /** 是否像分节标题（用于无底色块场景）。 */
    public static boolean isSectionName(String name) {
        if (name == null || name.isBlank()) {
            return false;
        }
        return SECTION_NAME_PATTERN.matcher(name.strip()).find();
    }

    /**
     * 「图片型页面」：位图（而非矢量线条）占页面面积的比例达到该值。
     *
     * <p>实测口径：客户图纸的参数表以 1536px 位图贴在页面上，覆盖率 59%；
     * 而纯矢量尺寸图页只有 0.1%、接线图页 5.5%；真实样册（324 页）中覆盖率 ≥30% 的仅 16 页，
     * 且都是整页图表/图纸。取 30% 能区分「图表型页面」与「图文混排的参数表页」。
     */
    public static final double IMAGE_PAGE_COVERAGE = 0.30;

    /**
     * 兜底 OCR 的图片占比门槛（比 {@link #IMAGE_PAGE_COVERAGE} 宽）。
     *
     * <p>只在「整本一张表都没解析出来」时才用得上：那时已确定文档是图纸/扫描件，
     * 「页面有没有 SPECIFICATION 标题」判不了（标题本身常在图片里），只能靠图片占比挑页。
     * 实测 ACD85 图纸参数表页 45.8%、接线图页 28.0% —— 阈值取 0.10 能把两者都纳入候选，
     * 接线图跑一次 OCR 只损失几秒、不会产生假表（表头校验会挡住）。
     */
    public static final double OCR_FALLBACK_COVERAGE = 0.10;

    /**
     * 参数表标题关键词：页面命中这些词、却没能识别出参数表时，判定为「参数表本身是图片」。
     *
     * <p>与「只是没有参数表的图纸页」区分开——后者不该提示用户去手工录入，
     * 也不该浪费时间去跑 OCR。
     */
    public static final Pattern SPEC_TITLE_PATTERN =
            Pattern.compile("(?i)(specifications?|\\bspec\\b|规格|参数表|技术参数|性能参数)");

    /** OCR 渲染分辨率：300dpi 下 8pt 正文约 33px 高，PP-OCR 识别稳定。 */
    public static final int OCR_DPI = 300;
    /** 单次转换最多 OCR 多少页：防止整本扫描件把转换拖成几分钟。 */
    public static final int OCR_MAX_PAGES = 12;

    /** 脚注正文左缘区间（编号列 / 正文列）。 */
    public static final double FOOTNOTE_NO_MIN_X = 36.0;
    public static final double FOOTNOTE_NO_MAX_X = 54.0;
    public static final double FOOTNOTE_BODY_MIN_X = 45.0;
    public static final double FOOTNOTE_BODY_MAX_X = 58.0;
    public static final double FOOTNOTE_BODY_RIGHT = 178.0;
}
