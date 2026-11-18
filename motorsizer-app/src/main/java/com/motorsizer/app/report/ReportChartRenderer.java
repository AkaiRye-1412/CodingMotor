package com.motorsizer.app.report;

import com.motorsizer.app.dto.CalculationResponse;

import javax.imageio.ImageIO;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Base64;
import java.util.List;

/**
 * S4 报告曲线：按 B1 的 sampling 数据在服务端重绘为 PNG 并内嵌为 data URI。
 *
 * <p>与前端 Canvas 共享同一份采样数据，但不共享渲染代码（02 §2.3.2）。
 */
public final class ReportChartRenderer {

    private static final int WIDTH = 1440;
    private static final int HEIGHT = 440;
    private static final double SCALE = 2.0;
    private static final Color GRID = new Color(0xEB, 0xEE, 0xF5);
    private static final Color AXIS = new Color(0x90, 0x93, 0x99);
    private static final Color STAGE = new Color(0xC0, 0xC4, 0xCC);

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
        return chart(t, v, accelerationEnd, cruiseEnd, unit, new Color(0x40, 0x9E, 0xFF));
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
        return chart(t, f, accelerationEnd, cruiseEnd, unit, new Color(0xE6, 0xA2, 0x3C));
    }

    private static String chart(
            double[] time,
            double[] value,
            double accelerationEnd,
            double cruiseEnd,
            String unit,
            Color color) {
        BufferedImage image = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, WIDTH, HEIGHT);
            g.scale(SCALE, SCALE);

            int width = (int) (WIDTH / SCALE);
            int height = (int) (HEIGHT / SCALE);
            int left = 56;
            int right = width - 18;
            int top = 18;
            int bottom = height - 34;

            if (time.length == 0) {
                g.setColor(AXIS);
                g.setFont(new Font("SansSerif", Font.PLAIN, 12));
                g.drawString("暂无数据", (left + right) / 2, (top + bottom) / 2);
                return toDataUri(image);
            }

            double tMax = Math.max(time[time.length - 1], 1e-9);
            double min = 0;
            double max = 0;
            for (double item : value) {
                min = Math.min(min, item);
                max = Math.max(max, item);
            }
            if (max - min < 1e-9) {
                max = min + 1;
            }
            double padding = (max - min) * 0.08;
            min = min < 0 ? min - padding : min;
            max = max + padding;

            g.setFont(new Font("SansSerif", Font.PLAIN, 11));
            g.setStroke(new BasicStroke(1f));
            for (int i = 0; i <= 4; i++) {
                double yValue = max - ((max - min) / 4) * i;
                int y = y(max, min, yValue, top, bottom);
                g.setColor(GRID);
                g.drawLine(left, y, right, y);
                g.setColor(AXIS);
                g.drawString(trim(yValue, 2), 4, y + 4);
            }
            for (int i = 0; i <= 4; i++) {
                double tValue = (tMax / 4) * i;
                int x = x(tMax, tValue, left, right);
                g.setColor(GRID);
                g.drawLine(x, top, x, bottom);
                g.setColor(AXIS);
                String label = trim(tValue, 2);
                g.drawString(label, x - g.getFontMetrics().stringWidth(label) / 2, height - 10);
            }

            g.setColor(STAGE);
            g.setStroke(new BasicStroke(1f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10f,
                    new float[]{4f, 4f}, 0f));
            drawStage(g, tMax, accelerationEnd, left, right, top, bottom);
            drawStage(g, tMax, cruiseEnd, left, right, top, bottom);

            g.setColor(color);
            g.setStroke(new BasicStroke(2f));
            for (int i = 1; i < time.length; i++) {
                g.drawLine(
                        x(tMax, time[i - 1], left, right), y(max, min, value[i - 1], top, bottom),
                        x(tMax, time[i], left, right), y(max, min, value[i], top, bottom));
            }

            g.setColor(AXIS);
            g.setFont(new Font("SansSerif", Font.PLAIN, 11));
            g.drawString(unit, left, 12);
            return toDataUri(image);
        } finally {
            g.dispose();
        }
    }

    private static void drawStage(
            Graphics2D g, double tMax, double stage, int left, int right, int top, int bottom) {
        if (stage <= 0 || stage >= tMax) {
            return;
        }
        int x = x(tMax, stage, left, right);
        g.drawLine(x, top, x, bottom);
    }

    private static int x(double tMax, double t, int left, int right) {
        return left + (int) Math.round((t / tMax) * (right - left));
    }

    private static int y(double max, double min, double value, int top, int bottom) {
        return top + (int) Math.round(((max - value) / (max - min)) * (bottom - top));
    }

    private static String trim(double value, int digits) {
        String text = String.format(java.util.Locale.ROOT, "%." + digits + "f", value);
        return text.contains(".") ? text.replaceAll("0+$", "").replaceAll("\\.$", "") : text;
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
