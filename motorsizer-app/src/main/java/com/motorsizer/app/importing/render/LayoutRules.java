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
