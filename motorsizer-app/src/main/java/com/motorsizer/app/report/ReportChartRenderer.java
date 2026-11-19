package com.motorsizer.app.report;

import com.motorsizer.app.dto.CalculationResponse;

import javax.imageio.ImageIO;
import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.Polygon;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Base64;
import java.util.List;
import java.util.Locale;

/**
 * S4 报告曲线：按 B1 的 sampling 数据在服务端重绘为 PNG 并内嵌为 data URI。
 *
 * <p>与前端 Canvas 共享同一份采样数据，但不共享渲染代码（02 §2.3.2）。
 * 第 69 轮重绘：刻度改为 1/2/5 取整（原来固定「最大值 / 4」，会出现 1.62、0.81 这类读不出来的刻度）、
 * 速度曲线加面积填充、峰值用虚线标注，网格与坐标轴按报告版式重新配色。
 */
public final class ReportChartRenderer {

    /** 逻辑画布（再按 {@link #SCALE} 放大两倍，避免 PDF 里发虚）。 */
    private static final int WIDTH = 720;
    private static final int HEIGHT = 152;
    private static final double SCALE = 2.0;

    private static final Color PLOT_BG = new Color(0xFB, 0xFC, 0xFE);
    private static final Color PLOT_BORDER = new Color(0xE4, 0xEA, 0xF2);
    private static final Color GRID = new Color(0xEC, 0xF1, 0xF7);
    private static final Color AXIS_TEXT = new Color(0x8A, 0x94, 0xA6);
    private static final Color ZERO_LINE = new Color(0xC7, 0xD2, 0xE0);
    private static final Color STAGE = new Color(0xC2, 0xCA, 0xD6);
    private static final Color PEAK = new Color(0x9A, 0xA5, 0xB4);

    private static final int PAD_LEFT = 40;
    private static final int PAD_RIGHT = 14;
    private static final int PAD_TOP = 18;
    private static final int PAD_BOTTOM = 24;

    private ReportChartRenderer() {
    }

    public static String speedChart(
            List<CalculationResponse.SpeedPoint> points,
            double accelerationEnd,
            double cruiseEnd,
            String unit) {
        double[] t = new double[points.size()];
        double[] v = new double[points.size()];
        for (int i = 0; i < points.size(); i++) {
            t[i] = points.get(i).t();
            v[i] = points.get(i).v();
        }
        return chart(t, v, accelerationEnd, cruiseEnd, unit,
                new Color(0x3B, 0x82, 0xF6), true);
    }

    public static String forceChart(
            List<CalculationResponse.ForcePoint> points,
            double accelerationEnd,
            double cruiseEnd,
            String unit) {
        double[] t = new double[points.size()];
        double[] f = new double[points.size()];
        for (int i = 0; i < points.size(); i++) {
            t[i] = points.get(i).t();
            f[i] = points.get(i).f();
        }
        return chart(t, f, accelerationEnd, cruiseEnd, unit,
                new Color(0xE0, 0x8A, 0x2E), false);
    }

    private static String chart(
            double[] time,
            double[] value,
            double accelerationEnd,
            double cruiseEnd,
            String unit,
            Color color,
            boolean fillArea) {

        BufferedImage image = new BufferedImage(
                (int) (WIDTH * SCALE), (int) (HEIGHT * SCALE), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                    RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, image.getWidth(), image.getHeight());
            g.scale(SCALE, SCALE);

            int left = PAD_LEFT;
            int right = WIDTH - PAD_RIGHT;
            int top = PAD_TOP;
            int bottom = HEIGHT - PAD_BOTTOM;

            g.setColor(PLOT_BG);
            g.fillRect(left, top, right - left, bottom - top);

            if (time.length == 0) {
                g.setColor(AXIS_TEXT);
                g.setFont(font(Font.PLAIN, 11f));
                drawCentered(g, "暂无曲线数据", (left + right) / 2, (top + bottom) / 2);
                g.setColor(PLOT_BORDER);
                g.drawRect(left, top, right - left, bottom - top);
                return toDataUri(image);
            }

            double tMax = Math.max(time[time.length - 1], 1e-9);
            double dataMin = 0;
            double dataMax = 0;
            for (double item : value) {
                dataMin = Math.min(dataMin, item);
                dataMax = Math.max(dataMax, item);
            }
            if (dataMax - dataMin < 1e-9) {
                dataMax = dataMin + 1;
            }

            double step = niceStep((dataMax - dataMin) / 4.0);
            double min = Math.floor(dataMin / step) * step;
            double max = Math.ceil(dataMax / step) * step;
            if (max - min < step) {
                max = min + step;
            }
            int tickDigits = decimalsFor(step);

            // 图区网格与坐标刻度
            g.setFont(font(Font.PLAIN, 10f));
            FontMetrics metrics = g.getFontMetrics();
            g.setStroke(new BasicStroke(1f));
            for (double tick = min; tick <= max + step * 0.5; tick += step) {
                int y = y(max, min, tick, top, bottom);
                g.setColor(GRID);
                g.drawLine(left, y, right, y);
                g.setColor(AXIS_TEXT);
                String label = trim(tick, tickDigits);
                g.drawString(label, left - 6 - metrics.stringWidth(label), y + 3);
            }
            for (int i = 0; i <= 4; i++) {
                double tValue = (tMax / 4) * i;
                int x = x(tMax, tValue, left, right);
                if (i > 0) {
                    g.setColor(GRID);
                    g.drawLine(x, top, x, bottom);
                }
                g.setColor(AXIS_TEXT);
                String label = trim(tValue, tMax >= 10 ? 0 : 2);
                g.drawString(label, x - metrics.stringWidth(label) / 2, bottom + 13);
            }

            // 零线（力 / 力矩曲线穿越零点，需要一条实线做参照）
            if (min < 0 && max > 0) {
                g.setColor(ZERO_LINE);
                int zero = y(max, min, 0, top, bottom);
                g.drawLine(left, zero, right, zero);
            }

            // 阶段分界
            g.setColor(STAGE);
            g.setStroke(new BasicStroke(1f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10f,
                    new float[]{3f, 3f}, 0f));
            drawStage(g, tMax, accelerationEnd, left, right, top, bottom);
            drawStage(g, tMax, cruiseEnd, left, right, top, bottom);

            int[] xs = new int[time.length];
            int[] ys = new int[time.length];
            for (int i = 0; i < time.length; i++) {
                xs[i] = x(tMax, time[i], left, right);
                ys[i] = y(max, min, value[i], top, bottom);
            }

            int baseline = y(max, min, clamp(0, min, max), top, bottom);
            if (fillArea) {
                g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.16f));
                g.setPaint(new GradientPaint(0, top, color, 0, baseline, new Color(255, 255, 255)));
                Polygon area = new Polygon();
                for (int i = 0; i < xs.length; i++) {
                    area.addPoint(xs[i], ys[i]);
                }
                area.addPoint(xs[xs.length - 1], baseline);
                area.addPoint(xs[0], baseline);
                g.fill(area);
                g.setComposite(AlphaComposite.SrcOver);
                g.setPaint(null);
            }

            g.setColor(color);
            g.setStroke(new BasicStroke(1.8f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            for (int i = 1; i < xs.length; i++) {
                g.drawLine(xs[i - 1], ys[i - 1], xs[i], ys[i]);
            }

            // 峰值 / 谷值标注：数值直接写在线上，省掉读者去猜刻度
            double peak = dataMax;
            double trough = dataMin;
            annotate(g, tMax, peak, min, max, left, right, top, bottom, "峰值", color, tickDigits);
            if (!fillArea && trough < -1e-9) {
                annotate(g, tMax, trough, min, max, left, right, top, bottom,
                        "谷值", color, tickDigits);
            }

            g.setColor(PLOT_BORDER);
            g.setStroke(new BasicStroke(1f));
            g.drawRect(left, top, right - left, bottom - top);

            // 轴标签：纵轴单位贴左上，横轴时间贴右下
            g.setColor(AXIS_TEXT);
            g.setFont(font(Font.PLAIN, 10f));
            if (unit != null && !unit.isBlank()) {
                g.drawString(unit, left, top - 8);
            }
            return toDataUri(image);
        } finally {
            g.dispose();
        }
    }

    private static void annotate(
            Graphics2D g,
            double tMax,
            double value,
            double min,
            double max,
            int left,
            int right,
            int top,
            int bottom,
            String prefix,
            Color color,
            int digits) {

        if (Math.abs(value) < 1e-9) {
            return;
        }
        int y = y(max, min, value, top, bottom);
        g.setColor(PEAK);
        g.setStroke(new BasicStroke(1f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10f,
                new float[]{2f, 3f}, 0f));
        g.drawLine(left, y, right, y);

        String text = prefix + " " + trim(value, digits);
        g.setFont(font(Font.BOLD, 10f));
        int width = g.getFontMetrics().stringWidth(text);
        int textY = y - 4;
        if (textY - 9 < top + 2) {
            textY = y + 13;
        }
        int textX = right - width - 4;
        g.setColor(new Color(0x7A, 0x86, 0x96));
        g.drawString(text, textX, textY);
    }

    private static void drawStage(
            Graphics2D g, double tMax, double stage, int left, int right, int top, int bottom) {
        if (stage <= 0 || stage >= tMax) {
            return;
        }
        int x = x(tMax, stage, left, right);
        g.drawLine(x, top, x, bottom);
    }

    /** 刻度步长取 1 / 2 / 2.5 / 5 × 10^n，避免出现 0.81 这类读不出来的刻度。 */
    private static double niceStep(double raw) {
        if (raw <= 0 || !Double.isFinite(raw)) {
            return 1;
        }
        double exponent = Math.floor(Math.log10(raw));
        double base = Math.pow(10, exponent);
        double fraction = raw / base;
        double nice;
        if (fraction <= 1) {
            nice = 1;
        } else if (fraction <= 2) {
            nice = 2;
        } else if (fraction <= 2.5) {
            nice = 2.5;
        } else if (fraction <= 5) {
            nice = 5;
        } else {
            nice = 10;
        }
        return nice * base;
    }

    private static int decimalsFor(double step) {
        if (step >= 1) {
            return 0;
        }
        int digits = (int) Math.ceil(-Math.log10(step) - 1e-9);
        return Math.min(Math.max(digits, 1), 3);
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private static Font font(int style, float size) {
        return new Font("SansSerif", style, Math.round(size))
                .deriveFont(size);
    }

    private static void drawCentered(Graphics2D g, String text, int x, int y) {
        FontMetrics metrics = g.getFontMetrics();
        g.drawString(text, x - metrics.stringWidth(text) / 2, y);
    }

    private static int x(double tMax, double t, int left, int right) {
        return left + (int) Math.round((t / tMax) * (right - left));
    }

    private static int y(double max, double min, double value, int top, int bottom) {
        if (max - min < 1e-12) {
            return bottom;
        }
        return top + (int) Math.round(((max - value) / (max - min)) * (bottom - top));
    }

    private static String trim(double value, int digits) {
        String text = String.format(Locale.ROOT, "%." + digits + "f", value);
        if (text.indexOf('.') >= 0) {
            text = text.replaceAll("0+$", "").replaceAll("\\.$", "");
        }
        return text.equals("-0") ? "0" : text;
    }

    private static String toDataUri(BufferedImage image) {
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            ImageIO.write(image, "png", output);
            return "data:image/png;base64," + Base64.getEncoder().encodeToString(output.toByteArray());
        } catch (IOException ex) {
            throw new UncheckedIOException("报告曲线渲染失败", ex);
        }
    }
}
