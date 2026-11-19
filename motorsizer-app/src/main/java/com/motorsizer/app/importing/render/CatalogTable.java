package com.motorsizer.app.importing.render;

import java.util.List;

/**
 * 一张参数表（一个型号在某页上的一格表）。
 *
 * @param page    来源页码（1-based，写入「页」列与识别报告）
 * @param model   型号名（样册原样，不含冷却后缀）
 * @param labels  接法列标签（串联/并联，或「型号+串联」）
 * @param configs 表头原始接法词
 * @param rows    表体行（含分节行）
 * @param variant 冷却变体：气冷 {@code -A}、水冷 {@code -W}，基型为 {@code NONE} / {@code SELF}
 */
public record CatalogTable(int page, String model, List<String> labels, List<String> configs,
                           List<TableRow> rows, CoolingVariants.Variant variant) {

    /** 没有冷却变体（绝大多数表）时的常用构造。 */
    public CatalogTable(int page, String model, List<String> labels, List<String> configs,
                        List<TableRow> rows) {
        this(page, model, labels, configs, rows, CoolingVariants.Variant.NONE);
    }

    /**
     * 输出型号 = 型号 + 冷却后缀。
     *
     * <p>后缀必须拼在<b>最后</b>：接法后缀（{@code 串联 → -S}）是写 Excel 时才加到型号后面的
     * （{@link CatalogWorkbookWriter#mergeModelConfig}），冷却后缀若提前拼进 {@link #model}，
     * 就会得到 {@code AXD80-56-A-S}，而用户要的是 {@code AXD80-56-S-A}。
     */
    public String outputModel() {
        return CoolingVariants.appendSuffix(model, variant);
    }

}
