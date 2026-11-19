package com.motorsizer.app.importing.ocr;

/**
 * OCR 识别出的一行文字及其在页面上的像素位置（左上角原点，与 BufferedImage 一致）。
 *
 * @param text  识别文本（已去除首尾空白）
 * @param x0    左边缘（px）
 * @param y0    上边缘（px）
 * @param x1    右边缘（px）
 * @param y1    下边缘（px）
 * @param score 识别置信度（0~1，取该行各字符概率的最小值）
 */
public record OcrTextLine(String text, int x0, int y0, int x1, int y1, float score) {

    public int width() {
        return x1 - x0;
    }

    public int height() {
        return y1 - y0;
    }

    public int centerY() {
        return (y0 + y1) / 2;
    }
}
