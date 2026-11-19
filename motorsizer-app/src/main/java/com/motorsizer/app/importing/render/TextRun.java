package com.motorsizer.app.importing.render;

/**
 * 一段连续排布、同字号的文字（对应 PyMuPDF rawdict 的 span）。
 *
 * <p>坐标口径与 PDF 页面显示一致：{@code x0/x1} 为左右边缘，{@code oy} 为基线 y（自页顶向下），
 * {@code size} 为字号（pt）。所有容差判断都基于这三者，与 pdf2excel.py 保持一致。
 */
public record TextRun(String text, double x0, double x1, double cx, double oy, double size) {

    public static TextRun of(String text, double x0, double x1, double oy, double size) {
        return new TextRun(text, x0, x1, (x0 + x1) / 2, oy, size);
    }

    /** 去空白后的文本（token 匹配用）。 */
    public String token() {
        return text.strip();
    }
}
