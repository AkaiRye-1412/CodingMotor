package com.motorsizer.app.importing.render;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 表内一行（脚本版里的 dict）。
 *
 * <p>字段可变是有意为之：续行合并、跨列合并、分节标记都在原对象上就地修改，
 * 与 pdf2excel.py 的处理顺序一一对应，避免引入第二套语义。
 */
public final class TableRow {

    public static final String KIND_ROW = "row";
    public static final String KIND_SECTION = "section";

    /** 行基线 y（自页顶向下）。 */
    double oy;
    /** 参数名（左侧列）。 */
    String name = "";
    /** 符号列。 */
    String sym = "";
    /** 单位列。 */
    String unit = "";
    /** 接法列 → 单元格文本，键为 v0/v1/…。 */
    final Map<String, String> values = new LinkedHashMap<>();
    /** 脚注编号（原样拼接）。 */
    String footnote = "";
    /** 是否由多行/跨列合并而来（Excel 里需要合并单元格）。 */
    boolean merged;
    /** row / section。 */
    String kind = KIND_ROW;
    /** 所属分节名。 */
    String section;
    /** 纵向合并标签的父级名称。 */
    String parent;

    public double oy() {
        return oy;
    }

    public String name() {
        return name;
    }

    public String sym() {
        return sym;
    }

    public String unit() {
        return unit;
    }

    public Map<String, String> values() {
        return values;
    }

    public String footnote() {
        return footnote;
    }

    public boolean merged() {
        return merged;
    }

    public String kind() {
        return kind;
    }

    boolean isSection() {
        return KIND_SECTION.equals(kind);
    }

    /** 值列表按列序（v0, v1, v2…）排列。 */
    java.util.List<String> orderedValues() {
        return values.entrySet().stream()
                .sorted(java.util.Comparator.comparingInt(e -> indexOf(e.getKey())))
                .map(Map.Entry::getValue)
                .toList();
    }

    static int indexOf(String key) {
        try {
            return Integer.parseInt(key.substring(1));
        } catch (RuntimeException ex) {
            return Integer.MAX_VALUE;
        }
    }
}
