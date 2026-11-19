package com.motorsizer.app.importing.commit;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 单台电机的入库结果（06 §6.1）。
 *
 * @param status {@code CREATED} 新增 / {@code UPDATED} 覆盖 / {@code SKIPPED} 跳过 / {@code FAILED} 失败
 * @param spec   这台电机已映射到的参数（K2 字段 key → 内部单位值）：前端点开「补全参数」时用它预填，
 *               缺的必填项自然是这里没有的 key
 */
public record ImportCommitItem(
        String modelName,
        String configName,
        String motorType,
        String structure,
        String cooling,
        String status,
        Long motorId,
        String message,
        Map<String, BigDecimal> spec) {

    public static final String CREATED = "CREATED";
    public static final String UPDATED = "UPDATED";
    public static final String SKIPPED = "SKIPPED";
    public static final String FAILED = "FAILED";

    public ImportCommitItem {
        // 保序复制：前端按模板顺序展示，Map.copyOf 不保证顺序
        spec = spec == null ? Map.of() : java.util.Collections.unmodifiableMap(new LinkedHashMap<>(spec));
    }

    static ImportCommitItem created(String model, String config, String type, String structure,
                                    String cooling, Long id, Map<String, BigDecimal> spec) {
        return new ImportCommitItem(model, config, type, structure, cooling, CREATED, id,
                "已新增到电机库", spec);
    }

    static ImportCommitItem updated(String model, String config, String type, String structure,
                                    String cooling, Long id, Map<String, BigDecimal> spec) {
        return new ImportCommitItem(model, config, type, structure, cooling, UPDATED, id,
                "电机库已有该主键，已整体覆盖", spec);
    }

    static ImportCommitItem skipped(String model, String config, String type, String structure,
                                    String cooling, String message, Map<String, BigDecimal> spec) {
        return new ImportCommitItem(model, config, type, structure, cooling, SKIPPED, null, message,
                spec);
    }

    static ImportCommitItem failed(String model, String config, String type, String structure,
                                   String cooling, String message, Map<String, BigDecimal> spec) {
        return new ImportCommitItem(model, config, type, structure, cooling, FAILED, null, message,
                spec);
    }
}
