package com.motorsizer.app.importing.render;

import org.apache.pdfbox.contentstream.PDFGraphicsStreamEngine;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.graphics.color.PDColor;
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

    /** 一页的扫描结果。 */
    public record PageScan(List<TextRun> runs, List<FillBand> bands, int charCount) {
    }

    /** 扫描指定页（1-based）。 */
    public PageScan scan(PDDocument doc, int pageNumber) throws IOException {
        if (pageNumber < 1 || pageNumber > doc.getNumberOfPages()) {
            return new PageScan(List.of(), List.of(), 0);
        }
        List<Glyph> glyphs = readGlyphs(doc, pageNumber);
        int chars = 0;
        for (Glyph g : glyphs) {
            if (!g.text().isBlank()) {
                chars += g.text().strip().length();
            }
        }
        return new PageScan(buildRuns(glyphs), readBands(doc.getPage(pageNumber - 1)), chars);
    }

    // ---------------------------------------------------------------- 文字

    /** 单个字形（PDFBox 的 TextPosition 投影）。 */
    private record Glyph(String text, double x0, double width, double oy, double size) {

        double x1() {
            return x0 + width;
        }
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
                    text.getY(), text.getFontSizeInPt()));
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

    // ---------------------------------------------------------------- 色块

    private List<FillBand> readBands(PDPage page) throws IOException {
        BandScanner scanner = new BandScanner(page);
        scanner.processPage(page);
        return scanner.bands;
    }

    /** 收集浅蓝填充矩形：与 pdf2excel.py 的 get_drawings() 过滤条件一一对应。 */
    private static final class BandScanner extends PDFGraphicsStreamEngine {

        private final double pageHeight;
        private final List<FillBand> bands = new ArrayList<>();
        private Rectangle2D.Double path;

        BandScanner(PDPage page) {
            super(page);
            this.pageHeight = page.getCropBox().getHeight();
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
            // 图表按图片绘制，与表格解析无关
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
