package com.motorsizer.app.importing;

import java.time.Instant;

/** I2 任务列表摘要项（不回传 pages/drafts 正文）。 */
public record ImportJobSummary(
        String jobId,
        JobStatus status,
        int fileCount,
        int pageParsed,
        int draftCount,
        Instant createdAt,
        long remainingDays) {   // 距过期清理剩余天数（Q3）
}
