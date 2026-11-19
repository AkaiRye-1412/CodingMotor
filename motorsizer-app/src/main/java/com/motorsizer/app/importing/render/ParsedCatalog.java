package com.motorsizer.app.importing.render;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 一本样册的解析结果（对应脚本版 {@code extract()} 的返回值）。
 *
 * @param source       源 PDF 路径
 * @param pageCount    总页数
 * @param charCount    抽到的有效字符数（判定是否扫描件）
 * @param hasTextLayer 是否含文字层（&gt; 500 字符）
 * @param tables       识别出的参数表
 * @param footnotes    脚注：编号 → 说明
 * @param skippedPages 未识别出参数表的页
 * @param imageTablePages 其中「本该有参数表、但参数表是位图」的页（图片占比高且页面出现规格类标题）
 * @param ocrPages     通过 OCR 才解析出参数表的页（数据来自图像识别，需重点核对）
 * @param sectionRows  分节名 → 数据行数（含被过滤掉的分节，保序）
 * @param excludedParams 采集口径下被剔除的参数 → 行数（如「最高线圈温度」，保序）
 */
public record ParsedCatalog(String source, int pageCount, int charCount, boolean hasTextLayer,
                            List<CatalogTable> tables, Map<Integer, String> footnotes,
                            List<Integer> skippedPages, List<Integer> imageTablePages,
                            List<Integer> ocrPages, Map<String, Integer> sectionRows,
                            Map<String, Integer> excludedParams) {

    /** 型号数（去重保序）。 */
    public int modelCount() {
        Set<String> models = new LinkedHashSet<>();
        for (CatalogTable t : tables) {
            models.add(t.model());
        }
        return models.size();
    }

    /** 参数行数（不含分节行）。 */
    public int parameterRows() {
        int n = 0;
        for (CatalogTable t : tables) {
            for (TableRow r : t.rows()) {
                if (!r.isSection()) {
                    n++;
                }
            }
        }
        return n;
    }

    /** 识别出参数表的页数。 */
    public int parsedPageCount() {
        return (int) tables.stream().mapToInt(CatalogTable::page).distinct().count();
    }

    /** 被分节过滤丢掉的数据行数（默认空名分组 = 表头区，永远保留、不计入）。 */
    public int droppedRows() {
        int n = 0;
        for (Map.Entry<String, Integer> e : sectionRows.entrySet()) {
            if (!e.getKey().isEmpty() && !LayoutRules.KEEP_SECTIONS.contains(e.getKey())) {
                n += e.getValue();
            }
        }
        return n;
    }

    /**
     * 被「采集口径」剔除的数据行数（保留分节里不要的项，如最高线圈温度）。
     *
     * <p>与 {@link #droppedRows()} 分开统计：分节过滤是「整块不要」，这里是「个别项不要」，
     * 报告里分开写，用户才知道某一行消失是哪种原因。
     */
    public int excludedRows() {
        if (excludedParams == null) {
            return 0;
        }
        int n = 0;
        for (int v : excludedParams.values()) {
            n += v;
        }
        return n;
    }
}
