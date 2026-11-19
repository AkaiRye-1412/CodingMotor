package com.motorsizer.app.importing;

import com.fasterxml.jackson.core.JacksonException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * job.json 文件持久化（06 v2 §2：任务不落数据库）。
 *
 * <p>每个任务一个目录 {@code {baseDir}/{jobId}/}，内含 {@code job.json}、{@code source/} 原 PDF
 * 与 {@code result.xlsx} 产物。保存时先写临时文件再移动，降低中途崩溃损坏风险。
 */
@Component
public class ImportStore {

    private static final Logger log = LoggerFactory.getLogger(ImportStore.class);

    private final ObjectMapper mapper;
    private final ImportConfig config;

    public ImportStore(ImportConfig config) {
        this.config = config;
        this.mapper = new ObjectMapper();
        this.mapper.registerModule(new JavaTimeModule());
        this.mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    public ObjectMapper mapper() {
        return mapper;
    }

    public void ensureBaseDir() throws IOException {
        Files.createDirectories(config.baseDir());
    }

    public void save(ImportJob job) throws IOException {
        Path dir = config.jobDir(job.jobId());
        Files.createDirectories(dir);
        Path target = config.jobFile(job.jobId());
        Path tmp = dir.resolve("job.json.tmp");
        mapper.writeValue(tmp.toFile(), job);
        Files.move(tmp, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                java.nio.file.StandardCopyOption.ATOMIC_MOVE);
    }

    public Optional<ImportJob> read(String jobId) throws IOException {
        Path file = config.jobFile(jobId);
        if (!Files.exists(file)) {
            return Optional.empty();
        }
        try {
            return Optional.of(mapper.readValue(file.toFile(), ImportJob.class));
        } catch (JacksonException ex) {
            // 06 v1（草稿链路）留下的目录：status 为 DRAFT/PARSED/UPLOADED 等已删除的枚举值，
            // 在 v2 语义下没有可用产物。按「不存在」处理，详情/下载统一走 404，不抛 500。
            log.debug("忽略旧版本任务（状态值已废弃）: {} ({})", jobId, ex.getOriginalMessage());
            return Optional.empty();
        }
    }

    /** 全部任务（按创建时间倒序）。 */
    public List<ImportJob> list() throws IOException {
        if (!Files.exists(config.baseDir())) {
            return List.of();
        }
        List<ImportJob> jobs = new ArrayList<>();
        try (Stream<Path> stream = Files.list(config.baseDir())) {
            stream.filter(Files::isDirectory).forEach(dir -> {
                try {
                    Path file = dir.resolve("job.json");
                    if (Files.exists(file)) {
                        jobs.add(mapper.readValue(file.toFile(), ImportJob.class));
                    }
                } catch (IOException ex) {
                    // 旧版本目录（06 v1）读不出来属预期，静默跳过，避免每次列表刷屏 WARN
                    log.debug("跳过不可读任务目录（可能为旧版本）: {} ({})", dir.getFileName(), ex.getMessage());
                }
            });
        }
        jobs.sort(Comparator.comparing(ImportJob::createdAt).reversed());
        return jobs;
    }

    public void delete(String jobId) throws IOException {
        Path dir = config.jobDir(jobId);
        if (Files.exists(dir)) {
            try (Stream<Path> walk = Files.walk(dir)) {
                walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                    try {
                        Files.deleteIfExists(p);
                    } catch (IOException ex) {
                        log.warn("删除失败: {} ({})", p, ex.getMessage());
                    }
                });
            }
        }
    }

    /** 启动期惰性清理过期任务（Q3）。返回清理条数。 */
    public int purgeExpired(Instant now) throws IOException {
        if (!Files.exists(config.baseDir())) {
            return 0;
        }
        int removed = 0;
        for (ImportJob job : list()) {
            long ageDays = Duration.between(job.createdAt(), now).toDays();
            if (ageDays > config.retentionDays()) {
                delete(job.jobId());
                removed++;
            }
        }
        return removed;
    }

    public ImportJobSummary summarize(ImportJob job, Instant now) {
        long remaining = Math.max(0,
                config.retentionDays() - Duration.between(job.createdAt(), now).toDays());
        ImportFileRef first = job.files().isEmpty() ? null : job.files().get(0);
        ImportStats stats = job.stats();
        return new ImportJobSummary(
                job.jobId(),
                job.status(),
                first == null ? "" : first.name(),
                stats == null ? 0 : stats.pageCount(),
                stats == null ? 0 : stats.tableCount(),
                stats == null ? 0 : stats.modelCount(),
                job.createdAt(),
                remaining);
    }
}
