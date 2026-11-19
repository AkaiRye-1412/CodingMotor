package com.motorsizer.app.importing.commit;

/**
 * 单台电机的入库结果（06 §6.1）。
 *
 * @param status {@code CREATED} 新增 / {@code UPDATED} 覆盖 / {@code SKIPPED} 跳过 / {@code FAILED} 失败
 */
public record ImportCommitItem(
        String modelName,
        String configName,
        String motorType,
        String structure,
        String cooling,
        String status,
        Long motorId,
        String message) {

    public static final String CREATED = "CREATED";
    public static final String UPDATED = "UPDATED";
    public static final String SKIPPED = "SKIPPED";
    public static final String FAILED = "FAILED";

    static ImportCommitItem created(String model, String config, String type, String structure,
                                    String cooling, Long id) {
        return new ImportCommitItem(model, config, type, structure, cooling, CREATED, id,
                "已新增到电机库");
    }

    static ImportCommitItem updated(String model, String config, String type, String structure,
                                    String cooling, Long id) {
        return new ImportCommitItem(model, config, type, structure, cooling, UPDATED, id,
                "电机库已有该主键，已整体覆盖");
    }

    static ImportCommitItem skipped(String model, String config, String type, String structure,
                                    String cooling, String message) {
        return new ImportCommitItem(model, config, type, structure, cooling, SKIPPED, null, message);
    }

    static ImportCommitItem failed(String model, String config, String type, String structure,
                                   String cooling, String message) {
        return new ImportCommitItem(model, config, type, structure, cooling, FAILED, null, message);
    }
}
