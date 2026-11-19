package com.motorsizer.app.importing.ocr;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;

/**
 * OCR 用的极简图像处理（缩放 / 裁剪 / 归一化），只依赖 java.awt，不引入 OpenCV。
 *
 * <p>PP-OCR 的输入约定：RGB 三通道、按 (x/255 - 0.5) / 0.5 归一化到 [-1, 1]。
 */
final class ImageOps {

    private ImageOps() {
    }

    /** 等比缩放到最长边不超过 maxSide（不放大）。 */
    static BufferedImage resizeToLimit(BufferedImage src, int maxSide) {
        int w = src.getWidth();
        int h = src.getHeight();
        double scale = Math.min(1.0, (double) maxSide / Math.max(w, h));
        if (scale >= 1.0) {
            return src;
        }
        return resize(src, Math.max(1, (int) Math.round(w * scale)), Math.max(1, (int) Math.round(h * scale)));
    }

    static BufferedImage resize(BufferedImage src, int w, int h) {
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.drawImage(src, 0, 0, w, h, null);
        g.dispose();
        return out;
    }

    /** 裁剪，越界部分用白色补齐（DB 检测框可能贴边）。 */
    static BufferedImage crop(BufferedImage src, int x, int y, int w, int h, int pad) {
        int x0 = Math.max(0, x - pad);
        int y0 = Math.max(0, y - pad);
        int x1 = Math.min(src.getWidth(), x + w + pad);
        int y1 = Math.min(src.getHeight(), y + h + pad);
        int cw = Math.max(1, x1 - x0);
        int ch = Math.max(1, y1 - y0);

        BufferedImage out = new BufferedImage(cw, ch, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        g.setColor(java.awt.Color.WHITE);
        g.fillRect(0, 0, cw, ch);
        // 把原图整体平移 -(x0,y0)，使裁剪区左上角落在 (0,0)；被画到负坐标的部分自动裁掉。
        g.drawImage(src, -x0, -y0, null);
        g.dispose();
        return out;
    }

    /**
     * 转成 CHW 浮点张量（RGB、[0,255] → [-1,1]）。
     *
     * @param dst 目标数组，长度 = 3 * w * h
     */
    static void fillChw(BufferedImage img, float[] dst) {
        int w = img.getWidth();
        int h = img.getHeight();
        int plane = w * h;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int rgb = img.getRGB(x, y);
                int i = y * w + x;
                dst[i] = (((rgb >> 16) & 0xFF) / 255f - 0.5f) / 0.5f;
                dst[plane + i] = (((rgb >> 8) & 0xFF) / 255f - 0.5f) / 0.5f;
                dst[2 * plane + i] = ((rgb & 0xFF) / 255f - 0.5f) / 0.5f;
            }
        }
    }
}
