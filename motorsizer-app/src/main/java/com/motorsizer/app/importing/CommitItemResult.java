package com.motorsizer.app.importing;

/** I12 批量提交单条结果（Q4：逐条独立事务）。 */
public record CommitItemResult(
        String draftId,
        boolean ok,
        Long motorId,        // 成功时返回新建电机 id
        String errorCode,    // 失败时错误码（如 MOTOR_DUPLICATE）
        String message,
        java.util.List<DraftIssue> issues) {

    public static CommitItemResult ok(String draftId, Long motorId) {
        return new CommitItemResult(draftId, true, motorId, null, "已入库", java.util.List.of());
    }

    public static CommitItemResult failed(String draftId, String errorCode, String message,
                                          java.util.List<DraftIssue> issues) {
        return new CommitItemResult(draftId, false, null, errorCode, message, issues);
    }
}
