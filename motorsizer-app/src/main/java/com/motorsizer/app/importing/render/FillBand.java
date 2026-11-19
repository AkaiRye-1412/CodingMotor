package com.motorsizer.app.importing.render;

/**
 * 页面上的浅蓝填充色块（样册的分节标题行）。
 *
 * <p>对应 PyMuPDF {@code page.get_drawings()} 里 fill 接近 {@code #CCE1F5} 的矩形；
 * 用于把「性能参数 / 其他信息」这类分节行还原到 Excel 里。
 */
public record FillBand(double y0, double y1, double x0, double x1) {

    public double width() {
        return x1 - x0;
    }

    public double height() {
        return y1 - y0;
    }

    public boolean coversY(double y, double tolerance) {
        return y >= y0 - tolerance && y <= y1 + tolerance;
    }
}
