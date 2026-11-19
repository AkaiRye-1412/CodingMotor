package com.motorsizer.app.importing.render;

import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 按冷却方式把一张参数表拆成多个型号（2026-11-19 用户裁定）。
 *
 * <p>样册把同一款电机的参数按冷却方式并排列出：
 * <pre>
 * 持续电流（自冷）@100℃  Icn  2.3
 * 持续电流（气冷）@100℃  Ica  2.8
 * 持续电流（水冷）@100℃  Icw  3.0
 * 峰值电流                Ipk  13.0
 * </pre>
 * 这样的表若原样转成一行型号，下游分不清三条持续电流各对应哪种冷却，入库时还会因为
 * 「同一字段重复出现」只留下第一条。拆开之后每个型号各是一套完整参数：
 * <ul>
 *   <li><b>基型</b> {@code AUM4-S1-S}：带「自冷」标注的行 + 不带任何冷却标注的行；</li>
 *   <li><b>气冷</b> {@code AUM4-S1-S-A}：带「气冷」标注的行 + 不带标注的行；</li>
 *   <li><b>水冷</b> {@code AUM4-S1-S-W}：带「水冷」标注的行 + 不带标注的行。</li>
 * </ul>
 *
 * <p>三条口径（用户裁定，改之前先看这里）：
 * <ol>
 *   <li>参数名<b>保留原样</b>，不去掉「（水冷）」这类标注——与样册逐字对得上；</li>
 *   <li>不带标注的通用参数三个型号<b>都带</b>，即「其他参数维持原型号」；</li>
 *   <li>某个参数只有气冷值、没有水冷值时，水冷型号<b>留空</b>，不拿别的冷却的值顶替；</li>
 *   <li>样册里<b>压根没有值</b>（写成 {@code -} / {@code —}）的，说明该型号不做那种冷却——
 *       不生成这个变体型号（见 {@link #partOf} 的列筛选）。</li>
 * </ol>
 *
 * <p>拆分只发生在写 Excel 这一步（{@link CatalogWorkbookWriter}），解析结果本身不动；
 * 入库读的是「参数汇总」页，因此拆出来的型号会各自入库为独立电机。
 */
public final class CoolingVariants {

    private CoolingVariants() {
    }

    /** 冷却变体。基型用 {@link #SELF} 表示：自冷行与未标注行都归基型。 */
    public enum Variant {
        /** 没有冷却标注的行（峰值电流这类通用参数）：每个变体都要收。 */
        NONE("", "未标注"),
        SELF("", "自冷"),
        AIR("-A", "气冷"),
        WATER("-W", "水冷");

        private final String suffix;
        private final String label;

        Variant(String suffix, String label) {
            this.suffix = suffix;
            this.label = label;
        }

        /** 型号后缀：基型不加，气冷 {@code -A}，水冷 {@code -W}。 */
        public String suffix() {
            return suffix;
        }

        /** 中文名（报告与提示语用）。 */
        public String label() {
            return label;
        }

        /** 该变体是否收这一行：未标注的行所有变体都收，带冷却标注的行只归自己。 */
        boolean accepts(Variant rowKind) {
            return rowKind == NONE || rowKind == this;
        }
    }

    /**
     * 拆分结果。
     *
     * @param tables      拆分后的表（一张源表可能变成 1~3 张）
     * @param splitModels 被拆过的源型号说明，如 {@code AUM4-S1-S → AUM4-S1-S-A、AUM4-S1-S-W}
     */
    public record Expansion(List<CatalogTable> tables, List<String> splitModels) {

        /** 被拆分过的源型号数。 */
        public int splitCount() {
            return splitModels.size();
        }
    }

    // ---------------------------------------------------------------- 词表

    /**
     * 中文冷却词：长词写在前面。
     *
     * <p>同义写法一并认（用户裁定）：自然冷却/自然对流 ≈ 自冷，风冷/空冷/强制风冷 ≈ 气冷，液冷 ≈ 水冷。
     */
    private static final String WORD_ZH = "自然冷却|自然对流|自扇冷|自冷"
            + "|强制风冷|强迫风冷|强制通风|气冷|风冷|空冷"
            + "|水冷|液冷";

    /**
     * 英文冷却词（客户图纸的参数名是英文）。
     *
     * <p>这里都带 {@code cool(ed|ing)} 之类的尾巴，不含裸的 {@code water}/{@code liquid}——
     * 裸词在括号里才是安全的（{@code Continuous Current (Water)}），
     * 出现在名字中间时多半是「冷却水流量」这类<b>参数本身</b>，不是冷却变体。
     */
    private static final String WORD_EN = "self[\\s-]*cool(?:ed|ing)?"
            + "|self[\\s-]*ventilated"
            + "|natural[\\s-]*(?:convection|cooling)"
            + "|air[\\s-]*cool(?:ed|ing)?"
            + "|forced[\\s-]*(?:air|ventilation)"
            + "|fan[\\s-]*cool(?:ed|ing)?"
            + "|water[\\s-]*cool(?:ed|ing)?"
            + "|liquid[\\s-]*cool(?:ed|ing)?";

    /** 括号里的冷却标注：{@code （水冷）}、{@code (Water)}、{@code [气冷]}、{@code (WC)}。 */
    private static final Pattern BRACKETED = Pattern.compile(
            "[（(【\\[]\\s*(" + WORD_ZH + "|" + WORD_EN + "|water|liquid|wc|ac)\\s*[）)】\\]]",
            Pattern.CASE_INSENSITIVE);

    /** 裸露在名字里的冷却标注：{@code 持续电流 水冷 @100℃}、{@code Continuous Current - Water cooled}。 */
    private static final Pattern BARE = Pattern.compile(
            "(?<![0-9A-Za-z\\u4e00-\\u9fff])(" + WORD_ZH + "|" + WORD_EN + ")"
                    + "(?![0-9A-Za-z\\u4e00-\\u9fff])",
            Pattern.CASE_INSENSITIVE);

    private static final Set<String> SELF_WORDS = Set.of(
            "自冷", "自然冷却", "自然对流", "自扇冷",
            "selfcool", "selfcooled", "selfcooling", "selfventilated",
            "naturalconvection", "naturalcooling");

    private static final Set<String> AIR_WORDS = Set.of(
            "气冷", "风冷", "空冷", "强制风冷", "强迫风冷", "强制通风",
            "ac",                                   // AC = Air Cooling（只认括号里的 (AC)）
            "aircool", "aircooled", "aircooling", "forcedair", "forcedventilation",
            "fancool", "fancooled", "fancooling");

    private static final Set<String> WATER_WORDS = Set.of(
            "水冷", "液冷", "water", "liquid",
            "wc",                                   // WC = Water Cooling（用户确认；只认括号里的 (WC)）
            "watercool", "watercooled", "watercooling",
            "liquidcool", "liquidcooled", "liquidcooling");

    // ---------------------------------------------------------------- 对外

    /** 拆分整本样册的表；同时记下「哪些型号被拆成了什么」供报告使用。 */
    public static Expansion expand(List<CatalogTable> tables) {
        List<CatalogTable> out = new ArrayList<>(tables.size());
        List<String> splitModels = new ArrayList<>();
        for (CatalogTable table : tables) {
            List<CatalogTable> parts = split(table);
            if (parts.size() > 1) {
                StringBuilder sb = new StringBuilder(table.model()).append(" → ");
                for (int i = 1; i < parts.size(); i++) {
                    if (i > 1) {
                        sb.append("、");
                    }
                    sb.append(parts.get(i).outputModel());
                }
                splitModels.add(sb.toString());
            }
            out.addAll(parts);
        }
        return new Expansion(List.copyOf(out), List.copyOf(splitModels));
    }

    /**
     * 单表拆分：没有任何冷却标注时原表原样返回（绝大多数样册走这条，零成本）。
     *
     * <p>拆出的表与原表<b>共用同一批行对象</b>——只做筛选，不改行内容（参数名保留原样）。
     */
    public static List<CatalogTable> split(CatalogTable table) {
        EnumSet<Variant> kinds = EnumSet.noneOf(Variant.class);
        for (TableRow row : table.rows()) {
            if (!row.isSection()) {
                kinds.add(classify(row.name()));
            }
        }
        boolean labeled = kinds.contains(Variant.SELF) || kinds.contains(Variant.AIR)
                || kinds.contains(Variant.WATER);
        if (!labeled) {
            return List.of(table);
        }

        List<CatalogTable> out = new ArrayList<>(3);
        // 基型（原名型号）：自冷行 + 未标注行。表里根本没有自冷数据时不出基型
        // （2026-11-19 裁定）——AKM/AKH 的 -W 系列只给水冷值，出基型会多一台缺
        // 「持续电流/持续推力」的残型号，入库时只能「跳过」，只会添乱。
        if (kinds.contains(Variant.SELF)) {
            addPart(out, table, Variant.SELF);
        }
        if (kinds.contains(Variant.AIR)) {
            addPart(out, table, Variant.AIR);
        }
        if (kinds.contains(Variant.WATER)) {
            addPart(out, table, Variant.WATER);
        }
        return out.isEmpty() ? List.of(table) : out;
    }

    /** 变体表没数据时 {@link #partOf} 返回 {@code null} —— 这个型号不该新增。 */
    private static void addPart(List<CatalogTable> out, CatalogTable table, Variant variant) {
        CatalogTable part = partOf(table, variant);
        if (part != null) {
            out.add(part);
        }
    }

    /**
     * 单个变体：只留「本变体的行 + 未标注的行」，分节行全留（「类别」列要用）；
     * 列也一并筛——见下面的列筛选说明。
     *
     * <p><b>列筛选（2026-11-19 第二次裁定）</b>：一张表常并排列着十几个型号，
     * 其中总有几个只给了自冷值，气冷/水冷那几行写的是 {@code -}（AUM5 的 S10、S12 就是这样）。
     * 这种列若跟着变体表一起输出，就会生成 {@code AUM5-S10-A} 这样缺「持续推力/持续电流」的
     * 残型号，导入时只能「跳过」。用户的原话是「样册里没有对应的值，那就说明没有水冷和气冷，
     * 型号就不用增加」，所以：
     * <ul>
     *   <li>本变体在<b>所有列</b>上都没有实值 → 整张变体表不生成；</li>
     *   <li>只在<b>部分列</b>有实值 → 只把这些列投影出来，其余列不参与该变体型号。</li>
     * </ul>
     *
     * @return 拆出的表；本变体没有任何实值时返回 {@code null}
     */
    private static CatalogTable partOf(CatalogTable table, Variant variant) {
        List<TableRow> rows = new ArrayList<>(table.rows().size());
        for (TableRow row : table.rows()) {
            if (row.isSection() || variant.accepts(classify(row.name()))) {
                rows.add(row);
            }
        }

        int cols = columnCount(table);
        boolean[] keep = new boolean[cols];
        int kept = 0;
        for (TableRow row : rows) {
            // 未标注的通用行（峰值电流这类）不是「这个型号有这种冷却」的证据：
            // 拿它当判据的话，每个型号都会因为「有峰值电流」而多出一台 -A / -W。
            if (row.isSection() || classify(row.name()) != variant) {
                continue;
            }
            for (int i = 0; i < cols; i++) {
                if (!keep[i] && !CatalogParser.isBlankValue(row.values.get("v" + i))) {
                    keep[i] = true;
                    kept++;
                }
            }
        }
        if (kept == 0) {
            return null;                        // 本表没有这种冷却方式的任何值
        }
        if (kept == cols) {
            return new CatalogTable(table.page(), table.model(), table.labels(), table.configs(),
                    rows, variant);
        }
        return project(table, rows, keep, variant);
    }

    /** 表的实际列数：标签数与原表值列数取大（个别表标签缺项，值列仍在）。 */
    private static int columnCount(CatalogTable table) {
        int cols = table.labels() == null ? 0 : table.labels().size();
        for (TableRow row : table.rows()) {
            for (String key : row.values.keySet()) {
                int i = TableRow.indexOf(key);
                if (i != Integer.MAX_VALUE) {
                    cols = Math.max(cols, i + 1);
                }
            }
        }
        return cols;
    }

    /**
     * 只保留 {@code keep} 标出的列：值列的键要<b>重新编号</b>（v2 剪掉后 v5 得变成 v4），
     * 否则 {@link CatalogTable#labels()} 的下标与 {@code vN} 就对不上了，Excel 里会串列。
     */
    private static CatalogTable project(CatalogTable table, List<TableRow> rows, boolean[] keep,
                                        Variant variant) {
        List<String> srcLabels = table.labels() == null ? List.of() : table.labels();
        int[] to = new int[keep.length];
        List<String> labels = new ArrayList<>(keep.length);
        List<String> configs = new ArrayList<>(keep.length);
        for (int i = 0; i < keep.length; i++) {
            if (!keep[i]) {
                continue;
            }
            to[i] = labels.size();
            labels.add(i < srcLabels.size() ? srcLabels.get(i) : "列" + (i + 1));
            if (table.configs() != null && i < table.configs().size()) {
                configs.add(table.configs().get(i));
            }
        }
        List<TableRow> projected = new ArrayList<>(rows.size());
        for (TableRow row : rows) {
            projected.add(copyWith(row, keep, to));
        }
        return new CatalogTable(table.page(), table.model(), List.copyOf(labels),
                List.copyOf(configs), projected, variant);
    }

    /** 复制一行，值只留 {@code keep} 的列并按 {@code to} 重新编号（其余字段原样带过）。 */
    private static TableRow copyWith(TableRow row, boolean[] keep, int[] to) {
        TableRow copy = new TableRow();
        copy.oy = row.oy;
        copy.name = row.name;
        copy.sym = row.sym;
        copy.unit = row.unit;
        copy.footnote = row.footnote;
        copy.merged = row.merged;
        copy.kind = row.kind;
        copy.section = row.section;
        copy.parent = row.parent;
        for (int i = 0; i < keep.length; i++) {
            if (!keep[i]) {
                continue;
            }
            String value = row.values.get("v" + i);
            if (value != null) {
                copy.values.put("v" + to[i], value);
            }
        }
        return copy;
    }

    /**
     * 追加冷却后缀；型号里<b>已经带同样的段</b>时不再追加（2026-11-19 裁定）。
     *
     * <p>AKM/AKH 的水冷型号本身就叫 {@code AKM100-W-B2-S}，再追加一次会变成
     * {@code AKM100-W-B2-S-W}。判据是「独立的 {@code -W-}/{@code -A-} 段」或「以它结尾」，
     * 不按子串判（{@code AUM4} 里的 {@code A} 不是冷却段）。
     */
    public static String appendSuffix(String model, Variant variant) {
        if (variant == null || variant.suffix().isEmpty()) {
            return model;
        }
        String suffix = variant.suffix();
        String segment = suffix.substring(1);
        String upper = model == null ? "" : model.toUpperCase(java.util.Locale.ROOT);
        if (upper.endsWith(suffix) || upper.contains("-" + segment + "-")) {
            return model;
        }
        return model + suffix;
    }

    /** 一行参数属于哪个冷却变体（按参数名里的标注判断，认不出就是未标注）。 */
    public static Variant classify(String name) {
        if (name == null || name.isBlank()) {
            return Variant.NONE;
        }
        Matcher bracketed = BRACKETED.matcher(name);
        while (bracketed.find()) {
            Variant v = kindOf(bracketed.group(1));
            if (v != Variant.NONE) {
                return v;
            }
        }
        Matcher bare = BARE.matcher(name);
        while (bare.find()) {
            Variant v = kindOf(bare.group(1));
            if (v != Variant.NONE) {
                return v;
            }
        }
        return Variant.NONE;
    }

    /**
     * 一个型号的数据行整体对应哪种冷却方式：{@code 水冷} / {@code 气冷} / {@code null}（自冷或未标注）。
     *
     * <p>入库时用它填电机库的「冷却方式」；自冷对应模板里的「普通」，所以这里返回 {@code null}
     * 让调用方取模板默认值。
     */
    public static String coolingLabel(Collection<String> names) {
        boolean water = false;
        boolean air = false;
        for (String name : names) {
            Variant v = classify(name);
            if (v == Variant.WATER) {
                water = true;
            } else if (v == Variant.AIR) {
                air = true;
            }
        }
        if (water) {
            return "水冷";
        }
        return air ? "气冷" : null;
    }

    private static Variant kindOf(String word) {
        String key = LayoutRules.keyword(word);
        if (SELF_WORDS.contains(key)) {
            return Variant.SELF;
        }
        if (AIR_WORDS.contains(key)) {
            return Variant.AIR;
        }
        if (WATER_WORDS.contains(key)) {
            return Variant.WATER;
        }
        return Variant.NONE;
    }

    /** 报告里的一句话口径（与上面的规则同源，改规则时同步改这里）。 */
    public static String rule() {
        return "参数名含「气冷」→ 型号追加 -A，含「水冷」→ 追加 -W；含「自冷」或不带冷却标注的留在原型号；"
                + "不带标注的通用参数各型号共用；某型号缺某项就留空，不借用其他冷却的值；"
                + "样册里该型号没有对应的值（写成 - 或 —）的，视为不做这种冷却，不新增型号";
    }
}
