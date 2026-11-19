package com.motorsizer.app.importing.render;

import org.apache.pdfbox.contentstream.PDFGraphicsStreamEngine;
import org.apache.pdfbox.contentstream.operator.Operator;
import org.apache.pdfbox.contentstream.operator.OperatorProcessor;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.graphics.PDXObject;
import org.apache.pdfbox.pdmodel.graphics.color.PDColor;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;
import org.apache.pdfbox.pdmodel.graphics.image.PDImage;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;
import org.apache.pdfbox.util.Matrix;
import org.springframework.stereotype.Component;

import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 页面扫描：把一页 PDF 变成「带坐标的文字 run + 分节底色块」。
 *
 * <p>这是 pdf2excel.py 里 {@code page_spans()} 与 {@code section_bands()} 的 Java 对应物：
 * <ul>
 *   <li>文字：继承 {@link PDFTextStripper} 收集每个字形的 (x, 基线 y, 字号)，先按基线聚成视觉行，
 *       再按水平间距切 run——不依赖 PDF 内容流顺序（样册里「单位 符号 串联 并联」常常排在页面末尾）。</li>
 *   <li>色块：继承 {@link PDFGraphicsStreamEngine} 取填充路径与填充色，识别分节标题行的浅蓝底纹。</li>
 * </ul>
 *
 * <p>坐标口径：x 自左缘、y 自页顶向下，单位 pt，与 PyMuPDF 的 rawdict 一致，便于与脚本版对照。
 */
@Component
public class PdfPageScanner {

    /**
     * 一页的扫描结果。
     *
     * @param runs           带坐标的文字段
     * @param bands          分节底色块
     * @param charCount      有效字符数
     * @param imageCoverage  位图覆盖页面的面积比例（0~1），用于识别「参数表是图片」
     */
    public record PageScan(List<TextRun> runs, List<FillBand> bands, int charCount, double imageCoverage) {
    }

    /** 扫描指定页（1-based）。 */
    public PageScan scan(PDDocument doc, int pageNumber) throws IOException {
        if (pageNumber < 1 || pageNumber > doc.getNumberOfPages()) {
            return new PageScan(List.of(), List.of(), 0, 0);
        }
        List<Glyph> glyphs = dropRotated(readGlyphs(doc, pageNumber));
        int chars = 0;
        for (Glyph g : glyphs) {
            if (!g.text().isBlank()) {
                chars += g.text().strip().length();
            }
        }
        PDPage page = doc.getPage(pageNumber - 1);
        PageGraphics gfx = readGraphics(page);
        double pageArea = page.getCropBox().getWidth() * page.getCropBox().getHeight();
        double coverage = pageArea <= 0 ? 0 : Math.min(1.0, gfx.imageArea() / pageArea);
        return new PageScan(buildRuns(glyphs), gfx.bands(), chars, coverage);
    }

    // ---------------------------------------------------------------- 文字

    /**
     * 单个字形（PDFBox 的 TextPosition 投影）。
     *
     * @param dir 文字方向角度（0 为水平；90/180/270 为旋转，取自 {@code TextPosition.getDir()}）
     */
    private record Glyph(String text, double x0, double width, double oy, double size, double dir) {

        double x1() {
            return x0 + width;
        }
    }

    /**
     * 丢掉旋转排版的字形（2026-11-19 真样册踩坑）。
     *
     * <p>尺寸图里的坐标/电缆标注常以 90° 旋转绘制，而 {@code x0} 取的是
     * {@code getXDirAdj()}——沿<b>文字方向</b>的偏移，不是页面 x。旋转 90° 时它会退化成
     * 「约等于基线 y」，于是标注被投影到参数表中间：ADR050-T-014 的「相间电阻」行单位变成
     * {@code 5Ω}、值变成 {@code 3.300.86；.0}，入库时报「缺少必填参数：相间电阻」。
     *
     * <p>整页都是旋转文字（图纸整页横排）时不丢——那时坐标口径本来就是文字方向的，
     * 丢了等于整页无字。判据：水平字形占多数才认为「旋转的是插页标注」。
     */
    private static boolean isHorizontal(Glyph g) {
        return Math.abs(g.dir()) < 1.0;
    }

    private static List<Glyph> dropRotated(List<Glyph> glyphs) {
        int horizontal = 0;
        for (Glyph g : glyphs) {
            if (isHorizontal(g)) {
                horizontal++;
            }
        }
        if (horizontal * 2 < glyphs.size()) {
            return glyphs;
        }
        List<Glyph> kept = new ArrayList<>(glyphs.size());
        for (Glyph g : glyphs) {
            if (isHorizontal(g)) {
                kept.add(g);
            }
        }
        return kept;
    }

    private List<Glyph> readGlyphs(PDDocument doc, int pageNumber) throws IOException {
        GlyphStripper stripper = new GlyphStripper();
        stripper.setStartPage(pageNumber);
        stripper.setEndPage(pageNumber);
        stripper.getText(doc);          // 返回值丢弃，坐标已收进 stripper.glyphs
        return stripper.glyphs;
    }

    private static final class GlyphStripper extends PDFTextStripper {

        private final List<Glyph> glyphs = new ArrayList<>();
        private final java.util.Set<String> seen = new java.util.HashSet<>();

        GlyphStripper() throws IOException {
            super();
            setSortByPosition(false);                   // 内容流顺序无所谓，聚类时按坐标重排
        }

        @Override
        protected void processTextPosition(TextPosition text) {
            String unicode = text.getUnicode();
            if (unicode == null || unicode.isEmpty()) {
                return;
            }
            // 样册常以「填充 + 描边」把同一字画两次：按 (字, 坐标) 去重，
            // 相当于 PDFBox 默认的 suppressDuplicateOverlappingText，这里手动实现以便自定义坐标口径。
            String key = unicode + '@' + Math.round(text.getXDirAdj() * 10)
                    + ',' + Math.round(text.getY() * 10);
            if (!seen.add(key)) {
                return;
            }
            // 用 getY()（真实基线 y）而非 getYDirAdj()：样册的坐标轴标注是旋转 90° 的文字，
            // getYDirAdj() 会按文字方向做投影，导致这些标注被错误地「投影」到表格区域里。
            glyphs.add(new Glyph(unicode, text.getXDirAdj(), text.getWidthDirAdj(),
                    text.getY(), text.getFontSizeInPt(), text.getDir()));
        }

        /** 只要坐标，不产出纯文本（默认实现会把字拼进输出缓冲）。 */
        @Override
        protected void writeString(String text, List<TextPosition> positions) {
            // no-op
        }
    }

    /**
     * 字形 → run：先按基线聚成视觉行（避免上下标打断 x 顺序），行内按 x 排序后按间距切段。
     */
    private List<TextRun> buildRuns(List<Glyph> glyphs) {
        if (glyphs.isEmpty()) {
            return List.of();
        }
        List<Glyph> byY = new ArrayList<>(glyphs);
        byY.sort(Comparator.comparingDouble(Glyph::oy));

        List<List<Glyph>> rows = new ArrayList<>();
        List<Double> rowBase = new ArrayList<>();
        for (Glyph g : byY) {
            List<Glyph> hit = null;
            for (int i = rows.size() - 1; i >= 0 && i >= rows.size() - 3; i--) {
                if (Math.abs(rowBase.get(i) - g.oy()) <= LayoutRules.BASELINE_TOLERANCE) {
                    hit = rows.get(i);
                    break;
                }
            }
            if (hit == null) {
                rows.add(new ArrayList<>(List.of(g)));
                rowBase.add(g.oy());
            } else {
                hit.add(g);
            }
        }

        List<TextRun> runs = new ArrayList<>();
        for (List<Glyph> row : rows) {
            row.sort(Comparator.comparingDouble(Glyph::x0));
            StringBuilder buf = new StringBuilder();
            double x0 = 0;
            double x1 = 0;
            double oy = 0;
            double size = 0;
            double prevRight = 0;
            boolean open = false;
            for (Glyph g : row) {
                boolean same = open
                        && Math.abs(g.size() - size) <= 0.35
                        && gapWithin(g.x0() - prevRight, size);
                if (same) {
                    buf.append(g.text());
                    x1 = Math.max(x1, g.x1());
                    prevRight = Math.max(prevRight, g.x1());
                    continue;
                }
                if (open) {
                    addRun(runs, buf, x0, x1, oy, size);
                }
                buf.setLength(0);
                buf.append(g.text());
                x0 = g.x0();
                x1 = g.x1();
                oy = g.oy();
                size = g.size();
                prevRight = g.x1();
                open = true;
            }
            if (open) {
                addRun(runs, buf, x0, x1, oy, size);
            }
        }
        return runs;
    }

    private boolean gapWithin(double gap, double size) {
        double tolerance = Math.min(LayoutRules.RUN_GAP_MAX,
                Math.max(LayoutRules.RUN_GAP_MIN, LayoutRules.RUN_GAP_FACTOR * size));
        // 轻微重叠（字距为负、连字）不切开；留 1.5pt 余量
        return gap >= -1.5 && gap <= tolerance;
    }

    private void addRun(List<TextRun> out, StringBuilder buf, double x0, double x1, double oy, double size) {
        String text = buf.toString();
        if (text.isBlank()) {
            return;                       // 纯空白 run 丢弃（与脚本版 after `if not t.strip(): continue` 一致）
        }
        out.add(TextRun.of(text, x0, x1, oy, Math.round(size * 10) / 10.0));
    }

    // ---------------------------------------------------------------- 色块与位图

    /** 一页的图形层统计：分节色块 + 位图总面积。 */
    private record PageGraphics(List<FillBand> bands, double imageArea) {
    }

    private PageGraphics readGraphics(PDPage page) throws IOException {
        BandScanner scanner = new BandScanner(page);
        scanner.processPage(page);
        return new PageGraphics(scanner.bands, scanner.imageArea);
    }

    /** 收集浅蓝填充矩形与位图绘制面积：与 pdf2excel.py 的 get_drawings() 过滤条件一一对应。 */
    private static final class BandScanner extends PDFGraphicsStreamEngine {

        private final double pageHeight;
        private final List<FillBand> bands = new ArrayList<>();
        private Rectangle2D.Double path;
        /** 位图覆盖面积累计（pt²）。 */
        private double imageArea;

        BandScanner(PDPage page) {
            super(page);
            this.pageHeight = page.getCropBox().getHeight();
            // 默认的 Do 处理器必须先把图解码成 PDImage 才会回调 drawImage：
            // 样册里的图是 JPEG2000，PDFBox 缺 JAI 解码器时整张图被跳过，面积就统计成 0。
            // 这里直接读 Do 操作符 + 当前 CTM，不依赖解码器，也不会被裁剪路径影响。
            addOperator(new OperatorProcessor() {
                @Override
                public void process(Operator operator, List<COSBase> arguments) throws IOException {
                    if (arguments.isEmpty()) {
                        return;
                    }
                    COSBase base = arguments.get(0);
                    if (!(base instanceof COSName)) {
                        return;
                    }
                    PDXObject xobject;
                    try {
                        xobject = getResources().getXObject((COSName) base);
                    } catch (IOException ex) {
                        return;                    // 资源损坏：跳过这张，不影响整页
                    }
                    if (xobject instanceof PDImage) {
                        addImageArea();
                    } else if (xobject instanceof PDFormXObject) {
                        showForm((PDFormXObject) xobject);   // 递归：Form 里的位图同样计入
                    }
                }

                @Override
                public String getName() {
                    return "Do";
                }
            });
        }

        /** 当前 CTM 下这张图的落纸面积（图像绘制在单位正方形上，面积 = |det|）。 */
        private void addImageArea() {
            Matrix ctm = getGraphicsState().getCurrentTransformationMatrix();
            double area = Math.abs(ctm.getScaleX() * ctm.getScaleY()
                    - ctm.getShearX() * ctm.getShearY());
            if (Double.isFinite(area) && area > 0) {
                imageArea += area;
            }
        }

        @Override
        public void appendRectangle(Point2D p0, Point2D p1, Point2D p2, Point2D p3) {
            include(p0);
            include(p1);
            include(p2);
            include(p3);
        }

        @Override
        public void moveTo(float x, float y) {
            include(x, y);
        }

        @Override
        public void lineTo(float x, float y) {
            include(x, y);
        }

        @Override
        public void curveTo(float x1, float y1, float x2, float y2, float x3, float y3) {
            include(x1, y1);
            include(x2, y2);
            include(x3, y3);
        }

        @Override
        public void closePath() {
            // 无需处理：包围盒已由各顶点确定
        }

        @Override
        public void endPath() {
            path = null;
        }

        @Override
        public void strokePath() {
            path = null;                 // 只有填充才是分节色块
        }

        @Override
        public void fillPath(int windingRule) {
            collect();
            path = null;
        }

        @Override
        public void fillAndStrokePath(int windingRule) {
            collect();
            path = null;
        }

        @Override
        public void clip(int windingRule) {
            path = null;                 // 裁剪路径不是可见底纹
        }

        @Override
        public void drawImage(PDImage pdImage) {
            // 面积统计统一在 Do 操作符处理里做（见构造器）：这里再算一次会重复累加。
        }

        @Override
        public void shadingFill(COSName shadingName) {
            // 渐变色块不使用
        }

        @Override
        public Point2D getCurrentPoint() {
            return path == null ? new Point2D.Double(0, 0)
                    : new Point2D.Double(path.x, path.y + path.height);
        }

        private void include(Point2D p) {
            include((float) p.getX(), (float) p.getY());
        }

        /** 用户空间 → 页面空间（应用 CTM），再转成自页顶向下的坐标。 */
        private void include(float x, float y) {
            Matrix ctm = getGraphicsState().getCurrentTransformationMatrix();
            double px = ctm.getScaleX() * x + ctm.getShearX() * y + ctm.getTranslateX();
            double py = ctm.getShearY() * x + ctm.getScaleY() * y + ctm.getTranslateY();
            double top = pageHeight - py;
            if (path == null) {
                path = new Rectangle2D.Double(px, top, 0, 0);
            } else {
                path.add(px, top);
            }
        }

        private void collect() {
            if (path == null) {
                return;
            }
            if (path.width < LayoutRules.BAND_MIN_WIDTH
                    || path.height < LayoutRules.BAND_MIN_HEIGHT
                    || path.height > LayoutRules.BAND_MAX_HEIGHT
                    || path.x > LayoutRules.BAND_MAX_X0) {
                return;
            }
            if (!isSectionFill()) {
                return;
            }
            bands.add(new FillBand(path.y, path.y + path.height, path.x, path.x + path.width));
        }

        private boolean isSectionFill() {
            PDColor color = getGraphicsState().getNonStrokingColor();
            if (color == null) {
                return false;
            }
            int rgb;
            try {
                rgb = color.toRGB();
            } catch (IOException | RuntimeException ex) {
                return false;             // 图案色 / 索引色等：不当作分节底纹
            }
            int target = LayoutRules.SECTION_FILL_RGB;
            int tolerance = LayoutRules.SECTION_FILL_TOLERANCE;
            return Math.abs(((rgb >> 16) & 0xFF) - ((target >> 16) & 0xFF)) <= tolerance
                    && Math.abs(((rgb >> 8) & 0xFF) - ((target >> 8) & 0xFF)) <= tolerance
                    && Math.abs((rgb & 0xFF) - (target & 0xFF)) <= tolerance;
        }
    }
}
