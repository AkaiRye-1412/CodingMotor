package com.motorsizer.app.importing;

import com.motorsizer.app.common.ApiResponse;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 样册 PDF → Excel 接口（06 v2 §7）。
 *
 * <p>Controller 不承载解析逻辑，只做参数绑定与信封封装。
 */
@RestController
@RequestMapping("/api/imports")
public class ImportController {

    private static final MediaType XLSX =
            MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");

    private final ImportService service;

    public ImportController(ImportService service) {
        this.service = service;
    }

    /** I1 上传 PDF 并生成 Excel。 */
    @PostMapping
    public ResponseEntity<ApiResponse<ImportJob>> convert(
            @RequestParam("file") MultipartFile file) throws IOException {
        ImportJob job = service.convert(file);
        String message = job.status() == JobStatus.READY ? "已生成 Excel" : "转换完成，但未识别到参数表";
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(message, job));
    }

    /** I2 任务列表（摘要）。 */
    @GetMapping
    public ApiResponse<List<ImportJobSummary>> list() throws IOException {
        return ApiResponse.ok(service.listJobs());
    }

    /** I3 任务详情。 */
    @GetMapping("/{jobId}")
    public ApiResponse<ImportJob> detail(@PathVariable String jobId) throws IOException {
        return ApiResponse.ok(service.getJob(jobId));
    }

    /** I4 删除任务（连同原 PDF 与产物）。 */
    @DeleteMapping("/{jobId}")
    public ApiResponse<Void> delete(@PathVariable String jobId) throws IOException {
        service.deleteJob(jobId);
        return ApiResponse.<Void>ok("任务已删除", null);
    }

    /** I5 下载生成的 Excel。 */
    @GetMapping("/{jobId}/export")
    public ResponseEntity<byte[]> export(@PathVariable String jobId) throws IOException {
        ImportService.ExportedFile file = service.export(jobId);
        ContentDisposition disposition = ContentDisposition.attachment()
                .filename(file.name(), StandardCharsets.UTF_8)
                .build();
        return ResponseEntity.ok()
                .contentType(XLSX)
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .body(file.bytes());
    }
}
