package com.motorsizer.app.importing.commit;

import java.util.List;

/**
 * 一次「入库」的结果汇总（06 §6.1）。
 *
 * <p>计数四分类互斥：{@code created} 新增、{@code updated} 覆盖、{@code skipped} 必填不全、
 * {@code failed} 校验/写库失败；{@code items} 逐台给出原因，前端据此列表展示。
 */
public record ImportCommitResult(
        String jobId,
        String fileName,
        int total,
        int created,
        int updated,
        int skipped,
        int failed,
        List<ImportCommitItem> items) {

    public ImportCommitResult {
        items = List.copyOf(items);
    }

    public static ImportCommitResult of(String jobId, String fileName, List<ImportCommitItem> items) {
        int created = 0;
        int updated = 0;
        int skipped = 0;
        int failed = 0;
        for (ImportCommitItem item : items) {
            switch (item.status()) {
                case ImportCommitItem.CREATED -> created++;
                case ImportCommitItem.UPDATED -> updated++;
                case ImportCommitItem.SKIPPED -> skipped++;
                default -> failed++;
            }
        }
        return new ImportCommitResult(jobId, fileName, items.size(),
                created, updated, skipped, failed, items);
    }

    /** 一句话结论，前端横幅与接口 message 共用。 */
    public String summary() {
        StringBuilder sb = new StringBuilder();
        if (created > 0) {
            sb.append("新增 ").append(created).append(" 台");
        }
        if (updated > 0) {
            if (sb.length() > 0) {
                sb.append("，");
            }
            sb.append("覆盖 ").append(updated).append(" 台");
        }
        if (skipped > 0) {
            if (sb.length() > 0) {
                sb.append("，");
            }
            sb.append("跳过 ").append(skipped).append(" 台（必填参数不全）");
        }
        if (failed > 0) {
            if (sb.length() > 0) {
                sb.append("，");
            }
            sb.append("失败 ").append(failed).append(" 台");
        }
        return sb.length() == 0 ? "没有可入库的电机" : sb.toString();
    }
}
