package com.motorsizer.app.importing;

import com.motorsizer.app.common.ApiResponse;
import com.motorsizer.app.dto.MotorUpsertRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;

/**
 * 样册识别接口（06 §7，I1–I13）。
 * Controller 不承载解析与校验逻辑；只做参数绑定与信封封装。
 */
@RestController
@RequestMapping("/api/imports")
public class ImportController {

    private final ImportService service;

    public ImportController(ImportService service) {
        this.service = service;
    }

    /** I1 创建任务（上传 PDF/图片）。 */
    @PostMapping
    public ResponseEntity<ApiResponse<ImportJob>> create(
            @RequestParam("files") List<MultipartFile> files) throws IOException {
        ImportJob job = service.createJob(files);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok("任务已创建", job));
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

    /** I4 取消/清理任务。 */
    @DeleteMapping("/{jobId}")
    public ApiResponse<Void> delete(@PathVariable String jobId) throws IOException {
        service.deleteJob(jobId);
        return ApiResponse.<Void>ok("任务已删除", null);
    }

    /** I5 页面列表。 */
    @GetMapping("/{jobId}/pages")
    public ApiResponse<List<ImportPage>> pages(@PathVariable String jobId) throws IOException {
        return ApiResponse.ok(service.listPages(jobId));
    }

    /** I6 页面提取文本（行数组）。 */
    @GetMapping("/{jobId}/pages/{pageId}/text")
    public ApiResponse<List<String>> pageText(@PathVariable String jobId,
                                              @PathVariable String pageId) throws IOException {
        return ApiResponse.ok(service.pageText(jobId, pageId));
    }

    /** I7 解析单页 → 生成/重建草稿。 */
    @PostMapping("/{jobId}/pages/{pageId}/recognize")
    public ApiResponse<List<Draft>> recognize(@PathVariable String jobId,
                                              @PathVariable String pageId) throws IOException {
        return ApiResponse.ok("已解析", service.recognizePage(jobId, pageId));
    }

    /** I8 草稿列表（可按 pageId 过滤）。 */
    @GetMapping("/{jobId}/drafts")
    public ApiResponse<List<Draft>> drafts(@PathVariable String jobId,
                                           @RequestParam(required = false) String pageId) throws IOException {
        return ApiResponse.ok(service.listDrafts(jobId, pageId));
    }

    /** I9 编辑草稿（整体替换 request）。 */
    @PutMapping("/{jobId}/drafts/{draftId}")
    public ApiResponse<Draft> editDraft(@PathVariable String jobId,
                                        @PathVariable String draftId,
                                        @RequestBody MotorUpsertRequest request) throws IOException {
        return ApiResponse.ok("已更新", service.editDraft(jobId, draftId, request));
    }

    /** I10 删除草稿。 */
    @DeleteMapping("/{jobId}/drafts/{draftId}")
    public ApiResponse<Void> deleteDraft(@PathVariable String jobId,
                                         @PathVariable String draftId) throws IOException {
        service.deleteDraft(jobId, draftId);
        return ApiResponse.<Void>ok("草稿已删除", null);
    }

    /** I11 单条校验。 */
    @PostMapping("/{jobId}/drafts/{draftId}/validate")
    public ApiResponse<List<DraftIssue>> validate(@PathVariable String jobId,
                                                  @PathVariable String draftId) throws IOException {
        return ApiResponse.ok(service.validateDraft(jobId, draftId));
    }

    /** I12 批量提交勾选草稿。 */
    @PostMapping("/{jobId}/commit")
    public ApiResponse<CommitResult> commit(@PathVariable String jobId) throws IOException {
        return ApiResponse.ok("提交完成", service.commit(jobId));
    }

    /** I13 勾选/取消勾选。 */
    @PostMapping("/{jobId}/drafts/{draftId}/checked")
    public ApiResponse<Draft> check(@PathVariable String jobId,
                                    @PathVariable String draftId,
                                    @RequestBody CheckedRequest body) throws IOException {
        return ApiResponse.ok(service.setChecked(jobId, draftId, body.checked()));
    }

    /** 导出识别草稿为 Excel（xlsx 下载，人工核对后回填）。 */
    @GetMapping("/{jobId}/export")
    public ResponseEntity<byte[]> export(@PathVariable String jobId) throws IOException {
        byte[] bytes = service.exportExcel(jobId);
        return ResponseEntity.ok()
                .header("Content-Type",
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
                .header("Content-Disposition",
                        "attachment; filename=import-" + jobId + ".xlsx")
                .body(bytes);
    }

    /** I13 请求体。 */
    public record CheckedRequest(boolean checked) {
    }
}
