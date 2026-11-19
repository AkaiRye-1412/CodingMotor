package com.motorsizer.app.report;

import com.motorsizer.app.common.BusinessException;
import com.motorsizer.app.common.ErrorCode;
import com.openhtmltopdf.outputdevice.helper.BaseRendererBuilder;
import com.openhtmltopdf.pdfboxout.PdfRendererBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;

/**
 * HTML/CSS → PDF（OpenHTMLtoPDF），并嵌入可商用的 Noto Sans SC 字体（02 R-4）。
 *
 * <p>常规与粗体分别注册：只注册单一字重时 {@code font-weight:700} 会被静默忽略，
 * 标题与表头会和正文一样细（第 69 轮报告美化踩过）。两份字体均由
 * {@code packaging/fonts-src/NotoSansSC-VF.ttf} 静态实例化后按 BMP 子集裁剪而来。
 */
@Component
public class ReportPdfRenderer {

    private static final Logger log = LoggerFactory.getLogger(ReportPdfRenderer.class);
    private static final String FONT_FAMILY = "Noto Sans SC";
    private static final String FONT_REGULAR = "/fonts/NotoSansSC-Regular.ttf";
    private static final String FONT_BOLD = "/fonts/NotoSansSC-Bold.ttf";

    public byte[] render(String html) {
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            PdfRendererBuilder builder = new PdfRendererBuilder();
            builder.useFastMode();
            registerFont(builder, FONT_REGULAR, 400, BaseRendererBuilder.FontStyle.NORMAL);
            registerFont(builder, FONT_BOLD, 700, BaseRendererBuilder.FontStyle.NORMAL);
            builder.withHtmlContent(html, null);
            builder.toStream(output);
            builder.run();
            return output.toByteArray();
        } catch (Exception ex) {
            log.error("报告 PDF 生成失败", ex);
            throw new BusinessException(ErrorCode.COMMON_INTERNAL_ERROR, "报告生成失败");
        }
    }

    private void registerFont(
            PdfRendererBuilder builder,
            String resource,
            int weight,
            BaseRendererBuilder.FontStyle style) {
        if (ReportPdfRenderer.class.getResource(resource) == null) {
            // 字体缺失时仍尝试生成，但中文可能显示为空白；由验收环节拦截（R-4）
            log.warn("未找到报告字体 {}，PDF 中文可能无法正常显示", resource);
            return;
        }
        builder.useFont(() -> ReportPdfRenderer.class.getResourceAsStream(resource),
                FONT_FAMILY, weight, style, true);
    }
}
