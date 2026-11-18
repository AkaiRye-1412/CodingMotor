package com.motorsizer.app.importing;

import java.util.List;

/** I12 批量提交结果。 */
public record CommitResult(
        String jobId,
        int total,
        int succeeded,
        int failed,
        List<CommitItemResult> items) {
}
