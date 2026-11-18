package com.motorsizer.app.importing;

import java.time.Instant;
import java.util.List;

/** 导入任务（06 §4 job.json 根）。 */
public record ImportJob(
        String jobId,
        JobStatus status,
        Instant createdAt,
        List<ImportFileRef> files,
        List<ImportPage> pages,
        List<Draft> drafts) {

    public ImportJob withStatus(JobStatus status) {
        return new ImportJob(jobId, status, createdAt, files, pages, drafts);
    }

    public ImportJob withPages(List<ImportPage> pages) {
        return new ImportJob(jobId, status, createdAt, files, pages, drafts);
    }

    public ImportJob withPagesAndDrafts(List<ImportPage> pages, List<Draft> drafts) {
        return new ImportJob(jobId, status, createdAt, files, pages, drafts);
    }

    public ImportJob withDrafts(List<Draft> drafts) {
        return new ImportJob(jobId, status, createdAt, files, pages, drafts);
    }
}
