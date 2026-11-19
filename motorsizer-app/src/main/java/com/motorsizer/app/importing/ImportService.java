package com.motorsizer.app.importing;

import com.motorsizer.app.common.BusinessException;
import com.motorsizer.app.common.ErrorCode;
import com.motorsizer.app.common.FieldError;
import com.motorsizer.app.importing.render.CatalogParser;
import com.motorsizer.app.importing.render.CatalogWorkbookWriter;
import com.motorsizer.app.importing.render.ParsedCatalog;
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
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 样册转换业务编排（06 v2）：上传 PDF → 版面解析 → 生成 Excel。
 *
 * <p>2026-11-19 改版：取消草稿、编辑、校验、勾选提交、写电机库等全部链路，
 * 本模块只产出一个 xlsx；电机库仍由手工录入维护。任务与产物存
 * {@code %APPDATA%\MotorSizer\imports\{jobId}\}，不落数据库。
 */
@Service
public class ImportService {

    private static final Logger log = LoggerFactory.getLogger(ImportService.class);
    private static final DateTimeFormatter JOBID_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private final ImportStore store;
    private final ImportConfig config;
    private final CatalogParser parser;
    private final CatalogWorkbookWriter writer;

    public ImportService(ImportStore store, ImportConfig config,
                         CatalogParser parser, CatalogWorkbookWriter writer) {
        this.store = store;
        this.config = config;
        this.parser = parser;
        this.writer = writer;
    }

    // ---------------------------------------------------------------- 转换

    /** 上传并转换：同步完成解析与写盘，返回带统计的任务。 */
    public ImportJob convert(MultipartFile upload) throws IOException {
        String original = validate(upload);
        String jobId = nextJobId();

        store.ensureBaseDir();
        Files.createDirectories(config.sourceDir(jobId));
        Path pdf = config.sourceDir(jobId).resolve("f1.pdf");
        Files.copy(upload.getInputStream(), pdf, StandardCopyOption.REPLACE_EXISTING);

        ParsedCatalog parsed;
        try {
            parsed = parser.parse(pdf);
        } catch (IOException | RuntimeException ex) {
            log.warn("样册解析失败: {} ({})", original, ex.getMessage(), ex);
            safeDelete(jobId);
            throw new BusinessException(ErrorCode.IMPORT_CONVERT_FAILED,
                    "PDF 解析失败：" + ex.getMessage());
        }

        CatalogWorkbookWriter.Result written;
        try {
            written = writer.write(parsed, config.resultFile(jobId));
        } catch (IOException | RuntimeException ex) {
            log.warn("Excel 生成失败: {} ({})", original, ex.getMessage(), ex);
            safeDelete(jobId);
            throw new BusinessException(ErrorCode.IMPORT_CONVERT_FAILED,
                    "Excel 生成失败：" + ex.getMessage());
        }

        ImportStats stats = buildStats(parsed, written, original);
        JobStatus status = parsed.tables().isEmpty() ? JobStatus.EMPTY : JobStatus.READY;
        ImportJob job = new ImportJob(jobId, status, Instant.now(),
                List.of(new ImportFileRef("f1", original, "pdf", parsed.pageCount(), upload.getSize())),
                stats);
        store.save(job);
        log.info("样册转换完成: {} 表={} 型号={} 行={} -> {}.xlsx",
                original, stats.tableCount(), stats.modelCount(), stats.dataRows());
        return job;
    }

    // ---------------------------------------------------------------- 查询

    public List<ImportJobSummary> listJobs() throws IOException {
        Instant now = Instant.now();
        return store.list().stream().map(j -> store.summarize(j, now)).toList();
    }

    public ImportJob getJob(String jobId) throws IOException {
        return requireJob(jobId);
    }

    public void deleteJob(String jobId) throws IOException {
        requireJob(jobId);
        store.delete(jobId);
        log.info("转换任务已删除: {}", jobId);
    }

    // ---------------------------------------------------------------- 下载

    /** 下载产物。 */
    public record ExportedFile(String name, byte[] bytes) {
    }

    public ExportedFile export(String jobId) throws IOException {
        ImportJob job = requireJob(jobId);
        Path xlsx = config.resultFile(jobId);
        if (!Files.exists(xlsx)) {
            throw new BusinessException(ErrorCode.IMPORT_JOB_NOT_FOUND,
                    "转换结果已清理，请重新上传样册");
        }
        String name = job.stats() == null || job.stats().excelName() == null
                ? "参数表.xlsx" : job.stats().excelName();
        return new ExportedFile(name, Files.readAllBytes(xlsx));
    }

    // ---------------------------------------------------------------- 启动清理

    public int purgeExpired() {
        try {
            int n = store.purgeExpired(Instant.now());
            if (n > 0) {
                log.info("清理过期转换任务 {} 个", n);
            }
            return n;
        } catch (IOException ex) {
            log.warn("过期任务清理失败: {}", ex.getMessage());
            return 0;
        }
    }

    // ---------------------------------------------------------------- 内部

    private String validate(MultipartFile upload) {
        if (upload == null || upload.isEmpty()) {
            throw new BusinessException(ErrorCode.IMPORT_FILE_REJECTED, "请上传一个 PDF 文件",
                    List.of(new FieldError("file", "未提供文件")));
        }
        String original = upload.getOriginalFilename();
        if (original == null || original.isBlank()) {
            original = "catalog.pdf";
        }
        if (!original.toLowerCase(Locale.ROOT).endsWith(".pdf")) {
            throw new BusinessException(ErrorCode.IMPORT_FILE_REJECTED,
                    "仅支持 PDF 文件（当前样册识别不做 OCR，图片无法解析）",
                    List.of(new FieldError("file", "不支持的文件类型：" + original)));
        }
        return original;
    }

    private ImportStats buildStats(ParsedCatalog parsed,
                                   CatalogWorkbookWriter.Result written, String original) {
        return new ImportStats(
                parsed.pageCount(),
                parsed.charCount(),
                parsed.hasTextLayer(),
                parsed.parsedPageCount(),
                parsed.tables().size(),
                parsed.modelCount(),
                parsed.parameterRows(),
                written.dataRows(),
                parsed.footnotes().size(),
                parsed.skippedPages(),
                excelNameOf(original),
                messageOf(parsed));
    }

    private String messageOf(ParsedCatalog parsed) {
        if (parsed.tables().isEmpty()) {
            return parsed.hasTextLayer()
                    ? "未识别到「符号 + 单位」表头的参数表，请查看 Excel 的「识别报告」页确认未识别页"
                    : "PDF 无文字层（扫描件），本模块不做 OCR；请改用带文字层的样册 PDF";
        }
        StringBuilder sb = new StringBuilder()
                .append("识别 ").append(parsed.tables().size()).append(" 张参数表")
                .append(" / ").append(parsed.modelCount()).append(" 个型号")
                .append(" / ").append(parsed.parameterRows()).append(" 条参数行");
        if (!parsed.footnotes().isEmpty()) {
            sb.append(" / ").append(parsed.footnotes().size()).append(" 条脚注");
        }
        if (parsed.droppedRows() > 0) {
            sb.append("；已按分节过滤掉 ").append(parsed.droppedRows()).append(" 行非性能参数");
        }
        if (!parsed.skippedPages().isEmpty()) {
            sb.append("；").append(parsed.skippedPages().size()).append(" 页未识别");
        }
        return sb.toString();
    }

    private String excelNameOf(String original) {
        String stem = original;
        int dot = stem.lastIndexOf('.');
        if (dot > 0) {
            stem = stem.substring(0, dot);
        }
        stem = stem.replaceAll("[\\\\/:*?\"<>|]", "_").strip();
        if (stem.isEmpty()) {
            stem = "样册";
        }
        return stem + "_参数表.xlsx";
    }

    private ImportJob requireJob(String jobId) throws IOException {
        Optional<ImportJob> job = store.read(jobId);
        if (job.isEmpty()) {
            throw new BusinessException(ErrorCode.IMPORT_JOB_NOT_FOUND,
                    "转换任务不存在或已清理: " + jobId);
        }
        return job.get();
    }

    private void safeDelete(String jobId) {
        try {
            store.delete(jobId);
        } catch (IOException ex) {
            log.warn("清理失败任务目录失败: {} ({})", jobId, ex.getMessage());
        }
    }

    private String nextJobId() {
        String time = Instant.now().atZone(ZoneOffset.ofHours(8)).format(JOBID_FORMAT);
        int rand = ThreadLocalRandom.current().nextInt(1000, 9999);
        return "imp-" + time + "-" + rand;
    }
}
