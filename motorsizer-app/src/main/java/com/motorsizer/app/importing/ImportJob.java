package com.motorsizer.app.importing;

import java.time.Instant;
import java.util.List;

/**
 * 转换任务（06 v2 §4 job.json 根）。
 *
 * <p>一次上传 = 一个任务 = 一个 xlsx；任务目录下的 {@code result.xlsx} 就是交付物。
 */
public record ImportJob(
        String jobId,
        JobStatus status,
        Instant createdAt,
        List<ImportFileRef> files,
        ImportStats stats) {
}
