package com.motorsizer.app.importing.render;

import java.util.List;

/**
 * 一张参数表（一个型号在某页上的一格表）。
 *
 * @param page    来源页码（1-based，写入「页」列与识别报告）
 * @param model   型号名
 * @param labels  接法列标签（串联/并联，或「型号+串联」）
 * @param configs 表头原始接法词
 * @param rows    表体行（含分节行）
 */
public record CatalogTable(int page, String model, List<String> labels, List<String> configs,
                           List<TableRow> rows) {
}
