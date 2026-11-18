package com.motorsizer.app.importing.recognize;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * PDF 文本层提取（06 §5 ①，P0）。
 *
 * <p>用 PDFBox {@link PDFTextStripper} 按页提取文本，按行拆分。
 * 无文本层的扫描 PDF 返回空行（页面状态由上层判为 OCR_REQUIRED/EMPTY）。
 * 依赖经 openhtmltopdf-pdfbox 传递引入（PDFBox 2.0.x，P0 不新增 Maven 依赖）。
 */
@Component
public class PdfTextExtractor {

    /** 提取指定页（1-based）的文本行。 */
    public PageText extractPage(Path pdfFile, int pageNumber) throws IOException {
        try (PDDocument doc = PDDocument.load(pdfFile.toFile())) {
            if (pageNumber < 1 || pageNumber > doc.getNumberOfPages()) {
                return new PageText(List.of());
            }
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setStartPage(pageNumber);
            stripper.setEndPage(pageNumber);
            String text = stripper.getText(doc);
            List<String> lines = new ArrayList<>();
            for (String raw : text.split("\\r?\\n")) {
                String line = raw.trim();
                if (!line.isEmpty()) {
                    lines.add(line);
                }
            }
            return new PageText(lines);
        }
    }

    /** PDF 总页数。 */
    public int pageCount(Path pdfFile) throws IOException {
        try (PDDocument doc = PDDocument.load(pdfFile.toFile())) {
            return doc.getNumberOfPages();
        }
    }
}
