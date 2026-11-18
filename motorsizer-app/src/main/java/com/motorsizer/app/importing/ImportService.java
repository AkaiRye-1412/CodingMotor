package com.motorsizer.app.importing;

import com.motorsizer.app.common.BusinessException;
import com.motorsizer.app.common.ErrorCode;
import com.motorsizer.app.common.FieldError;
import com.motorsizer.app.dto.MotorUpsertRequest;
import com.motorsizer.app.importing.recognize.PageRecognizer;
import com.motorsizer.app.importing.recognize.PageText;
import com.motorsizer.app.importing.recognize.PdfTextExtractor;
import com.motorsizer.app.service.MotorService;
import com.motorsizer.app.service.MotorSpecValidator;
import com.motorsizer.domain.catalog.MotorType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 样册识别业务编排（06 §7，I1–I13）。
 *
 * <p>边界：任务与草稿存 job.json（不落数据库）；写电机库唯一入口 = {@link MotorService#create}，
 * 复用 C3 校验与事务（Q4：逐条独立事务）。不触碰 K1/K3/S3/S4。
 */
@Service
public class ImportService {

    private static final Logger log = LoggerFactory.getLogger(ImportService.class);
    private static final DateTimeFormatter JOBID_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private final ImportStore store;
    private final ImportConfig config;
    private final PdfTextExtractor pdfExtractor;
    private final PageRecognizer recognizer;
    private final MotorSpecValidator validator;
    private final MotorService motorService;

    public ImportService(ImportStore store, ImportConfig config, PdfTextExtractor pdfExtractor,
                         PageRecognizer recognizer, MotorSpecValidator validator,
                         MotorService motorService) {
        this.store = store;
        this.config = config;
        this.pdfExtractor = pdfExtractor;
        this.recognizer = recognizer;
        this.validator = validator;
        this.motorService = motorService;
    }

    // ---------------------------------------------------------------- I1 创建

    public ImportJob createJob(List<MultipartFile> uploads) throws IOException {
        if (uploads == null || uploads.isEmpty()) {
            throw new BusinessException(ErrorCode.IMPORT_FILE_REJECTED, "请至少上传一个文件",
                    List.of(new FieldError("files", "未提供文件")));
        }
        String jobId = nextJobId();
        store.ensureBaseDir();
        Files.createDirectories(config.sourceDir(jobId));

        List<ImportFileRef> files = new ArrayList<>();
        List<ImportPage> pages = new ArrayList<>();
        int fileSeq = 1;
        for (MultipartFile upload : uploads) {
            String original = upload.getOriginalFilename() == null ? "unnamed" : upload.getOriginalFilename();
            String kind = detectKind(original);
            String fileId = "f" + fileSeq++;
            Path dest = config.sourceDir(jobId).resolve(fileId + extensionOf(original));
            Files.copy(upload.getInputStream(), dest, StandardCopyOption.REPLACE_EXISTING);

            int pageCount;
            if ("pdf".equals(kind)) {
                pageCount = pdfExtractor.pageCount(dest);
            } else {
                pageCount = 1;
            }
            files.add(new ImportFileRef(fileId, original, kind, pageCount));
            for (int p = 1; p <= pageCount; p++) {
                pages.add(ImportPage.pending(fileId + "p" + p, fileId, p));
            }
        }

        ImportJob job = new ImportJob(jobId, JobStatus.UPLOADED, Instant.now(), files, pages, List.of());
        store.save(job);

        // 上传即识别：对所有页面自动解析，避免用户逐页手动点「解析」。
        // 单页解析失败不影响其他页；最终返回反映解析后状态的 job。
        for (ImportPage page : pages) {
            try {
                recognizePage(jobId, page.pageId());
            } catch (Exception ex) {
                log.warn("自动解析页面失败: {} {} ({})", jobId, page.pageId(), ex.getMessage());
            }
        }
        return store.read(jobId).orElse(job);
    }

    // ---------------------------------------------------------------- I2 列表

    public List<ImportJobSummary> listJobs() throws IOException {
        Instant now = Instant.now();
        return store.list().stream().map(j -> store.summarize(j, now)).toList();
    }

    // ---------------------------------------------------------------- I3 详情

    public ImportJob getJob(String jobId) throws IOException {
        return requireJob(jobId);
    }

    // ---------------------------------------------------------------- I4 删除

    public void deleteJob(String jobId) throws IOException {
        ImportJob job = requireJob(jobId);
        store.delete(jobId);
        log.info("导入任务已删除: {}", jobId);
    }

    // ---------------------------------------------------------------- I5 页面列表

    public List<ImportPage> listPages(String jobId) throws IOException {
        return requireJob(jobId).pages();
    }

    // ---------------------------------------------------------------- I6 页面文本

    public List<String> pageText(String jobId, String pageId) throws IOException {
        ImportJob job = requireJob(jobId);
        ImportPage page = requirePage(job, pageId);
        ImportFileRef file = requireFile(job, page.fileId());
        if (!"pdf".equals(file.kind())) {
            throw new BusinessException(ErrorCode.IMPORT_OCR_REQUIRED,
                    "图片页无文本层，需 OCR 组件（后续版本）");
        }
        PageText text = pdfExtractor.extractPage(sourceFile(jobId, file, page), page.pageNumber());
        return text.lines();
    }

    // ---------------------------------------------------------------- I7 解析

    public List<Draft> recognizePage(String jobId, String pageId) throws IOException {
        ImportJob job = requireJob(jobId);
        ImportPage page = requirePage(job, pageId);
        ImportFileRef file = requireFile(job, page.fileId());

        PageStatus status;
        String message;
        List<Draft> drafts;

        if (!"pdf".equals(file.kind())) {
            status = PageStatus.OCR_REQUIRED;
            message = "图片页需 OCR 组件（后续版本）";
            drafts = List.of();
        } else {
            try {
                PageText text = pdfExtractor.extractPage(sourceFile(jobId, file, page), page.pageNumber());
                PageRecognizer.Result result = recognizer.recognize(text, page.pageId(), page.pageNumber(), file.name());
                status = result.status();
                message = result.message();
                drafts = result.drafts();
            } catch (IOException ex) {
                log.warn("页面解析失败: {} {} ({})", jobId, pageId, ex.getMessage());
                status = PageStatus.FAILED;
                message = "解析异常：" + ex.getMessage();
                drafts = List.of();
            }
        }

        // 替换该页草稿（幂等：先删旧）
        List<Draft> remaining = new ArrayList<>();
        for (Draft d : job.drafts()) {
            if (!pageId.equals(d.pageId())) remaining.add(d);
        }
        int seq = nextDraftSeq(remaining);
        List<Draft> renumbered = new ArrayList<>();
        for (Draft d : drafts) {
            renumbered.add(new Draft("d" + seq++, d.pageId(), d.sourcePage(), d.confidence(),
                    d.request(), d.rawFields(), d.issues(), d.checked()));
        }
        remaining.addAll(renumbered);

        final PageStatus statusFinal = status;
        final String messageFinal = message;
        final ImportFileRef fileFinal = file;
        List<ImportPage> pages = job.pages().stream()
                .map(p -> p.pageId().equals(pageId)
                        ? new ImportPage(p.pageId(), p.fileId(), p.pageNumber(), statusFinal, messageFinal,
                                "pdf".equals(fileFinal.kind()) ? safeLineCount(jobId, fileFinal, p) : 0)
                        : p)
                .toList();

        ImportJob updated = job.withPagesAndDrafts(pages, remaining);
        if (updated.status() == JobStatus.UPLOADED && status == PageStatus.RECOGNIZED) {
            updated = new ImportJob(updated.jobId(), JobStatus.PARSED, updated.createdAt(),
                    updated.files(), updated.pages(), updated.drafts());
        }
        store.save(updated);
        return renumbered;
    }

    private int safeLineCount(String jobId, ImportFileRef file, ImportPage p) {
        try {
            return pdfExtractor.extractPage(sourceFile(jobId, file, p), p.pageNumber()).lineCount();
        } catch (IOException ex) {
            return 0;
        }
    }

    // ---------------------------------------------------------------- I8 草稿列表

    public List<Draft> listDrafts(String jobId, String pageId) throws IOException {
        ImportJob job = requireJob(jobId);
        return job.drafts().stream()
                .filter(d -> pageId == null || pageId.equals(d.pageId()))
                .toList();
    }

    // ---------------------------------------------------------------- I9 编辑

    public Draft editDraft(String jobId, String draftId, MotorUpsertRequest request) throws IOException {
        ImportJob job = requireJob(jobId);
        Draft existing = requireDraft(job, draftId);
        List<DraftIssue> issues = validateQuiet(request);
        Draft replaced = new Draft(draftId, existing.pageId(), existing.sourcePage(),
                existing.confidence(), request, existing.rawFields(), issues, existing.checked());
        List<Draft> drafts = job.drafts().stream().map(d -> d.draftId().equals(draftId) ? replaced : d).toList();
        store.save(job.withDrafts(drafts));
        return replaced;
    }

    // ---------------------------------------------------------------- I10 删草稿

    public void deleteDraft(String jobId, String draftId) throws IOException {
        ImportJob job = requireJob(jobId);
        requireDraft(job, draftId);
        List<Draft> drafts = job.drafts().stream().filter(d -> !d.draftId().equals(draftId)).toList();
        store.save(job.withDrafts(drafts));
    }

    // ---------------------------------------------------------------- I11 校验

    public List<DraftIssue> validateDraft(String jobId, String draftId) throws IOException {
        ImportJob job = requireJob(jobId);
        Draft draft = requireDraft(job, draftId);
        List<DraftIssue> issues = validateQuiet(draft.request());
        Draft updated = new Draft(draft.draftId(), draft.pageId(), draft.sourcePage(),
                draft.confidence(), draft.request(), draft.rawFields(), issues, draft.checked());
        List<Draft> drafts = job.drafts().stream().map(d -> d.draftId().equals(draftId) ? updated : d).toList();
        store.save(job.withDrafts(drafts));
        return issues;
    }

    // ---------------------------------------------------------------- I12 提交

    public CommitResult commit(String jobId) throws IOException {
        ImportJob job = requireJob(jobId);
        List<Draft> checked = job.drafts().stream().filter(Draft::checked).toList();
        if (checked.isEmpty()) {
            throw new BusinessException(ErrorCode.IMPORT_NOTHING_TO_COMMIT, "请先勾选要提交的草稿");
        }

        List<CommitItemResult> items = new ArrayList<>();
        int succeeded = 0;
        int failed = 0;
        // 逐条独立事务（Q4）：每条单独调 MotorService.create，互不回滚
        List<Draft> updatedDrafts = new ArrayList<>(job.drafts());
        for (Draft draft : checked) {
            try {
                Long motorId = motorService.create(draft.request());
                items.add(CommitItemResult.ok(draft.draftId(), motorId));
                succeeded++;
                // 提交成功的草稿移除
                updatedDrafts = updatedDrafts.stream().filter(d -> !d.draftId().equals(draft.draftId())).toList();
            } catch (BusinessException ex) {
                List<DraftIssue> issues = ex.errors().stream()
                        .map(fe -> new DraftIssue(fe.field(), fe.reason())).toList();
                items.add(CommitItemResult.failed(draft.draftId(), ex.errorCode().name(), ex.getMessage(), issues));
                failed++;
                // 失败项保留并回填 issues
                updatedDrafts = updatedDrafts.stream()
                        .map(d -> d.draftId().equals(draft.draftId())
                                ? new Draft(d.draftId(), d.pageId(), d.sourcePage(), d.confidence(),
                                        d.request(), d.rawFields(), issues, d.checked())
                                : d)
                        .toList();
            } catch (RuntimeException ex) {
                log.warn("草稿提交未预期失败: {} {}", jobId, draft.draftId(), ex);
                items.add(CommitItemResult.failed(draft.draftId(), "COMMON_INTERNAL_ERROR",
                        "保存失败，请重试", List.of()));
                failed++;
            }
        }

        JobStatus newStatus = failed == 0 ? JobStatus.COMMITTED : job.status();
        store.save(new ImportJob(job.jobId(), newStatus, job.createdAt(),
                job.files(), job.pages(), updatedDrafts));
        return new CommitResult(jobId, checked.size(), succeeded, failed, items);
    }

    // ---------------------------------------------------------------- I13 勾选

    public Draft setChecked(String jobId, String draftId, boolean checked) throws IOException {
        ImportJob job = requireJob(jobId);
        Draft draft = requireDraft(job, draftId);
        Draft updated = new Draft(draft.draftId(), draft.pageId(), draft.sourcePage(),
                draft.confidence(), draft.request(), draft.rawFields(), draft.issues(), checked);
        List<Draft> drafts = job.drafts().stream().map(d -> d.draftId().equals(draftId) ? updated : d).toList();
        store.save(job.withDrafts(drafts));
        return updated;
    }

    // ---------------------------------------------------------------- 启动清理

    public int purgeExpired() {
        try {
            int n = store.purgeExpired(Instant.now());
            if (n > 0) {
                log.info("清理过期导入任务 {} 个", n);
            }
            return n;
        } catch (IOException ex) {
            log.warn("过期任务清理失败: {}", ex.getMessage());
            return 0;
        }
    }

    // ---------------------------------------------------------------- 内部

    private ImportJob requireJob(String jobId) throws IOException {
        Optional<ImportJob> job = store.read(jobId);
        if (job.isEmpty()) {
            throw new BusinessException(ErrorCode.IMPORT_JOB_NOT_FOUND, "导入任务不存在或已清理: " + jobId);
        }
        return job.get();
    }

    private ImportPage requirePage(ImportJob job, String pageId) {
        for (ImportPage p : job.pages()) {
            if (p.pageId().equals(pageId)) return p;
        }
        throw new BusinessException(ErrorCode.IMPORT_PAGE_NOT_FOUND, "页面不存在: " + pageId);
    }

    private ImportFileRef requireFile(ImportJob job, String fileId) {
        for (ImportFileRef f : job.files()) {
            if (f.fileId().equals(fileId)) return f;
        }
        throw new BusinessException(ErrorCode.IMPORT_FILE_REJECTED, "文件不存在: " + fileId);
    }

    private Draft requireDraft(ImportJob job, String draftId) {
        for (Draft d : job.drafts()) {
            if (d.draftId().equals(draftId)) return d;
        }
        throw new BusinessException(ErrorCode.IMPORT_DRAFT_NOT_FOUND, "草稿不存在: " + draftId);
    }

    private Path sourceFile(String jobId, ImportFileRef file, ImportPage page) {
        // 文件名 = fileId + 原扩展；此处按已知扩展重建
        String ext = extensionOf(file.name());
        return config.sourceDir(jobId).resolve(file.fileId() + ext);
    }

    private List<DraftIssue> validateQuiet(MotorUpsertRequest request) {
        try {
            validator.validate(request, null);
            return List.of();
        } catch (BusinessException ex) {
            return ex.errors().stream().map(fe -> new DraftIssue(fe.field(), fe.reason())).toList();
        }
    }

    private int nextDraftSeq(List<Draft> existing) {
        int max = 0;
        for (Draft d : existing) {
            try {
                int n = Integer.parseInt(d.draftId().substring(1));
                if (n > max) max = n;
            } catch (NumberFormatException ignored) {
            }
        }
        return max + 1;
    }

    private String nextJobId() {
        String time = Instant.now().atZone(ZoneOffset.ofHours(8)).format(JOBID_FORMAT);
        int rand = ThreadLocalRandom.current().nextInt(1000, 9999);
        return "imp-" + time + "-" + rand;
    }

    private static String detectKind(String filename) {
        String lower = filename.toLowerCase();
        if (lower.endsWith(".pdf")) return "pdf";
        if (lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image";
        throw new BusinessException(ErrorCode.IMPORT_FILE_REJECTED, "仅支持 PDF 与 PNG/JPG 图片",
                List.of(new FieldError("files", "不支持的文件类型: " + filename)));
    }

    private static String extensionOf(String filename) {
        int idx = filename.lastIndexOf('.');
        return idx >= 0 ? filename.substring(idx) : "";
    }

    // 仅供测试与潜在的字段检测使用
    static MotorType detectType(MotorUpsertRequest r) {
        return MotorType.fromCode(r.motorType());
    }

    // 抑制未用警告：UUID 备用命名
    @SuppressWarnings("unused")
    private static String uuid() {
        return UUID.randomUUID().toString();
    }
}
