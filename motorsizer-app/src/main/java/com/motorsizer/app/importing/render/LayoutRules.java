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

    /** 单元格内多行文字的 y 容差（合并单元格的第二三行）。 */
    public static final double CELL_LINE_TOLERANCE = 3.0;

    /** 续行归属：与有名行的最大 y 距离。 */
    public static final double CONTINUATION_ATTACH = 11.0;

    /** 其他信息里的纵向合并标签：上下 10.5pt 内的行归为子项。 */
    public static final double LABEL_ATTACH = 10.5;

    /** 表块至少要有几行「有参数名」才算识别成功（过滤公告/目录页）。 */
    public static final int MIN_NAMED_ROWS = 4;

    /**
     * 只保留这些分节（2026-11-19 用户裁定：只抓性能参数；机械参数/编码器参数/其他信息一律丢弃）。
     *
     * <p>白名单而非黑名单：样册里同一含义的分节写法不统一（「其他信息」/「其它信息」），
     * 名单外的分节一律不进 Excel，新增要保留的分节直接加到集合里。
     */
    public static final Set<String> KEEP_SECTIONS = Set.of("性能参数");

    /** 脚注正文左缘区间（编号列 / 正文列）。 */
    public static final double FOOTNOTE_NO_MIN_X = 36.0;
    public static final double FOOTNOTE_NO_MAX_X = 54.0;
    public static final double FOOTNOTE_BODY_MIN_X = 45.0;
    public static final double FOOTNOTE_BODY_MAX_X = 58.0;
    public static final double FOOTNOTE_BODY_RIGHT = 178.0;
}
