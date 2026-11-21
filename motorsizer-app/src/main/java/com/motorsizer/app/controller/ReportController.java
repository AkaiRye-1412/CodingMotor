package com.motorsizer.app.controller;

import com.motorsizer.app.common.ApiResponse;
import com.motorsizer.app.dto.ReportRequest;
import com.motorsizer.app.report.ReportFileSaver;
import com.motorsizer.app.report.ReportService;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

/**
 * D1 导出报告 PDF。
 *
 * <p>两个入口：
 * <ul>
 *   <li>{@code POST /api/reports/pdf} —— 直接返回二进制流，浏览器下载（原有契约，不变）；</li>
 *   <li>{@code POST /api/reports/pdf/save} —— 先弹系统「另存为」对话框让用户自选路径，
 *       再生成 PDF 写盘，返回保存位置（打包成桌面应用时的主路径）。</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/reports")
public class ReportController {

    private final ReportService reportService;
    private final ReportFileSaver fileSaver;

    public ReportController(ReportService reportService, ReportFileSaver fileSaver) {
        this.reportService = reportService;
        this.fileSaver = fileSaver;
    }

    @PostMapping(value = "/pdf", produces = MediaType.APPLICATION_PDF_VALUE)
    public ResponseEntity<byte[]> exportPdf(@RequestBody ReportRequest request) {
        ReportService.ReportPdf pdf = reportService.generate(request);
        ContentDisposition disposition = ContentDisposition.attachment()
                .filename(pdf.filename(), StandardCharsets.UTF_8)
                .build();
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .contentType(MediaType.APPLICATION_PDF)
                .contentLength(pdf.content().length)
                .body(pdf.content());
    }

    /**
     * 「另存为」：用户取消时返回 {@code saved=false}（不是错误），
     * 前端据此静默收尾；无图形环境时由 {@link ReportFileSaver} 抛
     * {@code REPORT_SAVE_UNAVAILABLE}，前端回退到 {@code /pdf} 下载。
     */
    @PostMapping("/pdf/save")
    public ApiResponse<SaveResult> savePdf(@RequestBody ReportRequest request) {
        Path target = fileSaver.chooseSavePath(reportService.suggestedFilename(request));
        if (target == null) {
            return ApiResponse.ok("已取消保存", new SaveResult(false, null, null));
        }
        ReportService.ReportPdf pdf = reportService.generate(request);
        fileSaver.write(target, pdf.content());
        return ApiResponse.ok("报告已保存",
                new SaveResult(true, target.toString(), target.getFileName().toString()));
    }

    /** saved=false 表示用户在对话框里取消了（此时 path / filename 为空）。 */
    public record SaveResult(boolean saved, String path, String filename) {
    }
}
