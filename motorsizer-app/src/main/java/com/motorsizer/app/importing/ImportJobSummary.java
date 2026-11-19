package com.motorsizer.app.importing;

import java.time.Instant;

/** 任务列表摘要项（不回传逐页明细）。 */
public record ImportJobSummary(
        String jobId,
        JobStatus status,
        String fileName,
        int pageCount,
        int tableCount,
        int modelCount,
        Instant createdAt,
        long remainingDays) {
}
