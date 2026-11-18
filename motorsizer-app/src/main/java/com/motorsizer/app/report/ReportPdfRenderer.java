package com.motorsizer.app.report;

import com.motorsizer.app.common.BusinessException;
import com.motorsizer.app.common.ErrorCode;
import com.openhtmltopdf.pdfboxout.PdfRendererBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URL;

/** HTML/CSS → PDF（OpenHTMLtoPDF），并嵌入可商用的 Noto Sans SC 字体（02 R-4）。 */
@Component
public class ReportPdfRenderer {

    private static final Logger log = LoggerFactory.getLogger(ReportPdfRenderer.class);
    private static final String FONT_FAMILY = "Noto Sans SC";
    private static final String FONT_RESOURCE = "/fonts/NotoSansSC-VF.ttf";

    public byte[] render(String html) {
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            PdfRendererBuilder builder = new PdfRendererBuilder();
            builder.useFastMode();
            builder.withHtmlContent(html, null);
            registerFont(builder);
            builder.toStream(output);
            builder.run();
            return output.toByteArray();
        } catch (Exception ex) {
            log.error("报告 PDF 生成失败", ex);
            throw new BusinessException(ErrorCode.COMMON_INTERNAL_ERROR, "报告生成失败");
        }
    }

    private void registerFont(PdfRendererBuilder builder) {
        URL fontUrl = ReportPdfRenderer.class.getResource(FONT_RESOURCE);
        if (fontUrl == null) {
            // 字体缺失时仍尝试生成，但中文可能显示为空白；由验收环节拦截（R-4）
            log.warn("未找到报告字体 {}，PDF 中文可能无法正常显示", FONT_RESOURCE);
            return;
        }
        builder.useFont(this::openFontStream, FONT_FAMILY);
    }

    private InputStream openFontStream() {
        try {
            return ReportPdfRenderer.class.getResourceAsStream(FONT_RESOURCE);
        } catch (RuntimeException ex) {
            throw ex;
        }
    }
}
