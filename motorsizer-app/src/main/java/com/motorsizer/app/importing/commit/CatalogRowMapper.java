package com.motorsizer.app.importing.commit;

import com.motorsizer.app.importing.render.CoolingVariants;
import com.motorsizer.app.service.MotorCooling;
import com.motorsizer.domain.catalog.MotorCatalogTemplates;
import com.motorsizer.domain.catalog.MotorField;
import com.motorsizer.domain.catalog.MotorType;
import com.motorsizer.domain.catalog.MotorTypeTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 「参数汇总」行 → 一台电机的入库请求（06 §6.1）。
 *
 * <p>三件事，全部以 K2 模板为唯一来源（字段字典不硬编码）：
 * <ol>
 *   <li><b>定类型</b>：拿每一行去三种模板里试着匹配字段，命中行数最多的模板胜出
 *       （力矩样册的 {@code Tcn/Tpk/Kt/2p/Ωmax} 只有力矩模板有，音圈的 {@code 行程} 只有音圈模板有）；</li>
 *   <li><b>取值</b>：<b>符号优先</b>（{@code R25} 比「相间电阻 @25°C ±10%」稳），符号重复或缺失时退回
 *       参数名（标签 + 别名，先精确后去测试条件后精确，最后才用唯一包含）；</li>
 *   <li><b>定主键</b>：<b>型号名原样取「参数汇总」的型号列</b>（06 §7／第 65 轮裁定），
 *       配置名恒为「默认」——Excel 里写着 {@code AXD120-71-S}，库里就是 {@code AXD120-71-S}，
 *       不再把 {@code -S}/{@code -P} 拆成配置名。</li>
 * </ol>
 *
 * <p>必填缺失的电机不会被「用 0 补」——直接标记跳过并列出缺哪个字段，交给用户核对样册。
 */
@Component
public class CatalogRowMapper {

    /** 一台待入库电机。{@code missing} 非空即无法入库（必填 = K2 req 集合）。 */
    public record MappedMotor(
            String modelName,
            String configName,
            MotorType type,
            String structure,
            String cooling,
            Map<String, BigDecimal> spec,
            List<String> missing,
            List<String> notes) {
    }

    private static final Pattern NUMBER = Pattern.compile("-?\\d+(?:\\.\\d+)?(?:[Ee][+-]?\\d+)?");

    /** 纯 ASCII（含数字与下划线）：短别名撞词中段的误判只对这种写法发生。 */
    private static final Pattern ASCII = Pattern.compile("[\\x20-\\x7e]+");

    /** 未带接法的型号（以及全部型号，见 {@link #keyOf}）用的配置名。 */
    private static final String CONFIG_DEFAULT = "默认";

    /** 质量换算：样册的 g 落成内部 kg（CAL-25）。 */
    private static final BigDecimal GRAM_TO_KG = new BigDecimal("0.001");

    private static final BigDecimal KILOGRAM_TO_GRAM = new BigDecimal("1000");

    /** 按型号分组保序映射；同一个型号的多次出现只出一台电机。 */
    public List<MappedMotor> map(List<CatalogSummarySheet.SheetRow> rows) {
        Map<String, List<CatalogSummarySheet.SheetRow>> byModel = new LinkedHashMap<>();
        for (CatalogSummarySheet.SheetRow row : rows) {
            byModel.computeIfAbsent(row.model(), key -> new ArrayList<>()).add(row);
        }
        List<MappedMotor> motors = new ArrayList<>(byModel.size());
        byModel.forEach((model, modelRows) -> motors.add(mapOne(model, modelRows)));
        return motors;
    }

    private MappedMotor mapOne(String model, List<CatalogSummarySheet.SheetRow> rows) {
        MotorType type = detectType(rows);
        MotorTypeTemplate template = MotorCatalogTemplates.of(type);

        Map<String, BigDecimal> spec = new LinkedHashMap<>();
        List<String> notes = new ArrayList<>();
        Set<String> unmapped = new LinkedHashSet<>();

        for (CatalogSummarySheet.SheetRow row : rows) {
            MotorField field = resolveField(template, row);
            if (field == null) {
                unmapped.add(describe(row));
                continue;
            }
            if (spec.containsKey(field.key())) {
                // 同一字段出现两次（如串联/并联共用一格 + 另一行）：以第一次为准，留痕不静默
                notes.add(field.label() + " 重复出现，取首次的值");
                continue;
            }
            BigDecimal value = parseNumber(row.value());
            if (value == null) {
                notes.add(field.label() + " 的值「" + row.value() + "」不是数值，已忽略");
                continue;
            }
            spec.put(field.key(), value.multiply(scaleOf(field, row.unit())));
        }

        // 最高线圈温度：采集口径里剔掉了这一行，但热校核（允许电流 / 是否超温）必须有它，
        // 用户裁定「都按默认 100 °C」。这里补值而不是留空，这样电机库、报告、界面看到的是同一个数。
        if (!spec.containsKey("tMax")) {
            spec.put("tMax", BigDecimal.valueOf(MotorCatalogTemplates.DEFAULT_MAX_COIL_TEMP));
            notes.add("样册未提供最高线圈温度，按默认 "
                    + (int) MotorCatalogTemplates.DEFAULT_MAX_COIL_TEMP + " °C 入库（热校核用）");
        }

        List<String> missing = new ArrayList<>(template.requiredKeys().stream()
                .filter(key -> !spec.containsKey(key))
                .map(key -> labelOf(template, key))
                .toList());
        // 「录入必填」（直线/音圈的线圈质量、力矩的转动惯量，2026-11-20 用户裁定）：
        // 样册里没有这一项就整台跳过，不留空入库。它们不在模板 required 里，
        // 因为 required 会连带把选型准入判成 UNKNOWN（历史电机都没有这两项）。
        for (String key : MotorCatalogTemplates.entryRequiredKeys(type)) {
            if (!spec.containsKey(key)) {
                missing.add(labelOf(template, key));
            }
        }
        if (!unmapped.isEmpty()) {
            List<String> shown = unmapped.stream().limit(5).toList();
            notes.add("未映射的参数：" + String.join("、", shown) + (unmapped.size() > 5 ? " 等" : ""));
        }

        String[] key = keyOf(model);
        return new MappedMotor(
                key[0], key[1], type,
                template.structures().isEmpty() ? "" : template.structures().get(0),
                coolingOf(template, key[0], rows),
                spec, missing, notes);
    }

    /**
     * 冷却方式：**样册证据优先，型号结尾后缀兜底**，都没有才是模板首项（普通 = 自冷）。
     *
     * <ol>
     *   <li><b>样册证据</b>：该型号的数据行里写着「水冷」/「气冷」/「(WC)」/「(AC)」
     *       → 直接定（{@link CoolingVariants#coolingLabel}）。参数行是被冷却方式标注过的，
     *       比型号名更贴近数据本身；</li>
     *   <li><b>型号结尾</b> {@code -A} / {@code -W}（{@link MotorCooling#bySuffix}）——
     *       AKM/AKH 的水冷表参数名写的是英文 {@code (WC)}，中文词表抓不到时靠这条；
     *       只看结尾、<b>不看中间段</b>，因为 {@code AKM200-A-B4} 的 {@code -A-} 是结构代号；</li>
     *   <li>都没有 → 模板首项（直线/力矩是「普通」，音圈只有「普通」）。</li>
     * </ol>
     *
     * <p>模板里没有该选项时（音圈只有「普通」）一律退回首项，绝不写一个非法值
     * ——库表有 CHECK 约束 {@code CK_MOTOR_COOLING}。
     */
    private static String coolingOf(MotorTypeTemplate template, String model,
                                    List<CatalogSummarySheet.SheetRow> rows) {
        String label = CoolingVariants.coolingLabel(
                rows.stream().map(CatalogSummarySheet.SheetRow::name).toList());
        if (label == null) {
            label = MotorCooling.bySuffix(model);
        }
        if (label != null && template.allowsCooling(label)) {
            return label;
        }
        return template.coolings().isEmpty() ? "" : template.coolings().get(0);
    }

    // ---------------------------------------------------------------- 类型

    /**
     * 判定电机类型。
     *
     * <p><b>第一步：转矩类参数是旋转电机的决定性证据</b>（2026-11-20 用户裁定）。
     * 只要命中 {@code torqueCont}（持续转矩）/ {@code torquePeak}（峰值转矩）/ {@code kt}（转矩常数）
     * 里的 ≥2 个，直接判 TORQUE。
     *
     * <p>这条规则是为 <b>ACR 弧形电机</b>加的：样册把它归在「直线电机」章节（原文
     * 「ACR 系列为无铁芯圆弧型直线电机，也叫弧形电机」），参数表里也带「磁吸力 / 电磁周期 /
     * 线圈质量」这些直线电机才有的行——按「命中字段数最多」判定时，直线模板与力矩模板各命中
     * 13 项打平，代码取靠前的 LINEAR 胜出，于是入库去找「持续推力 / 峰值推力」，
     * ACR 全系列被判「缺少必填参数」整批跳过。而它的参数全是转矩（{@code Tcn 19.8 Nm}）、
     * 转速、转动惯量，运动也是旋转（有限角度或 360°），必须走**旋转页签**计算。
     *
     * <p><b>第二步：其余按命中字段数最多者</b>（直线 → 力矩 → 音圈顺序，平手取靠前）。
     * 音圈与直线共用「持续推力 / 峰值推力 / 力常数」的字段名，靠独有项
     * {@code 行程 / 铁芯质量 / 运动间隙} 拉开差距（音圈 17 项 vs 直线 14 项），
     * 所以不能用「独有字段命中数」直接比——那样音圈会被判成直线。
     */
    private MotorType detectType(List<CatalogSummarySheet.SheetRow> rows) {
        if (hasTorqueSignature(rows)) {
            return MotorType.TORQUE;
        }
        MotorType best = MotorType.LINEAR;
        int bestHits = -1;
        for (MotorType type : MotorCatalogTemplates.typeOrder()) {
            if (type == MotorType.TORQUE) {
                continue;                       // 转矩证据已在上面否决过，不再参与命中数竞争
            }
            MotorTypeTemplate template = MotorCatalogTemplates.of(type);
            Set<String> keys = new LinkedHashSet<>();
            for (CatalogSummarySheet.SheetRow row : rows) {
                MotorField field = resolveField(template, row);
                if (field != null) {
                    keys.add(field.key());
                }
            }
            if (keys.size() > bestHits) {
                bestHits = keys.size();
                best = type;
            }
        }
        return best;
    }

    /** 旋转电机的决定性字段：持续转矩 / 峰值转矩 / 转矩常数。 */
    private static final Set<String> TORQUE_SIGNATURE_KEYS =
            Set.of("torqueCont", "torquePeak", "kt");

    /**
     * 是否命中 ≥2 个转矩类字段（持续转矩 / 峰值转矩 / 转矩常数）。
     *
     * <p>阈值取 2 而不是 1：单看「转矩常数」一行不够稳（个别图纸会把「转矩常数」当附属信息列），
     * 但「持续转矩 + 峰值转矩」同时出现就一定是旋转电机。
     */
    private boolean hasTorqueSignature(List<CatalogSummarySheet.SheetRow> rows) {
        MotorTypeTemplate torque = MotorCatalogTemplates.of(MotorType.TORQUE);
        int hits = 0;
        for (CatalogSummarySheet.SheetRow row : rows) {
            MotorField field = resolveField(torque, row);
            if (field != null && TORQUE_SIGNATURE_KEYS.contains(field.key())) {
                hits++;
            }
        }
        return hits >= 2;
    }

    // ---------------------------------------------------------------- 字段

    private MotorField resolveField(MotorTypeTemplate template, CatalogSummarySheet.SheetRow row) {
        List<MotorField> fields = template.fields();
        String symbol = normSymbol(row.symbol());
        if (!symbol.isEmpty()) {
            List<MotorField> bySymbol = fields.stream()
                    .filter(field -> normSymbol(field.symbol()).equals(symbol))
                    .toList();
            if (bySymbol.size() == 1) {
                return bySymbol.get(0);
            }
            if (bySymbol.size() > 1) {
                // 力矩的「最高转速 @峰值转矩 / @持续转矩」都写作 Ωmax，只能靠参数名区分
                return pickByName(bySymbol, row.name());
            }
            MotorField byCoolingSymbol = byCoolingSuffixSymbol(fields, symbol);
            if (byCoolingSymbol != null) {
                return byCoolingSymbol;
            }
        }
        return pickByName(fields, row.name());
    }

    /**
     * 带冷却尾字母的符号：{@code Kthw} / {@code Icw} / {@code Pcw}（水冷）、{@code Ktha} / {@code Ica} / {@code Pca}（气冷）。
     *
     * <p>样册（AKM 系列英文图纸）把同一字段按冷却方式加一个尾字母，模板里的符号不带后缀：
     * {@code Kthw} 对不上 {@code Kth}，于是「热耗散常数」整行丢弃 → 该型号缺必填、整台跳过
     * （2026-11-19 用户反馈 `AKM200-A-B4-W-B` 只缺热耗散常数）。
     *
     * <p>判定很简单：行符号以 {@code w}/{@code a} 收尾时，去掉它先按原样比（{@code Kthw → Kth}），
     * 再补自冷形式的收尾 {@code n}（{@code Icw → Ic → Icn}、{@code Pcw → Pc → Pcn}）。
     * 长度不足 3 或尾字母不是 {@code w}/{@code a} 的直接放弃，避免把 {@code Tmax} 这类正常符号拆坏。
     */
    private MotorField byCoolingSuffixSymbol(List<MotorField> fields, String symbol) {
        char tail = symbol.charAt(symbol.length() - 1);
        if (symbol.length() < 3 || (tail != 'W' && tail != 'A')) {
            return null;
        }
        String stem = symbol.substring(0, symbol.length() - 1);
        for (String candidate : List.of(stem, stem + "N")) {
            List<MotorField> hits = fields.stream()
                    .filter(field -> normSymbol(field.symbol()).equals(candidate))
                    .toList();
            if (hits.size() == 1) {
                return hits.get(0);
            }
        }
        return null;
    }

    /**
     * 参数名匹配：①标签/别名精确相等 → ②去掉 {@code @100°C}、{@code ±10%} 之类测试条件后相等 →
     * ③唯一「包含」{@code (标签, 别名)}。三步都可能落到多个候选，多候选即视为不确定，返回 {@code null}
     * ——宁可跳过让用户核对，也不猜一个字段把值写错。
     */
    private MotorField pickByName(List<MotorField> fields, String name) {
        String exact = normName(name);              // 保留 @测试条件
        String loose = normName(name, true);        // 去掉 @测试条件
        if (exact.isEmpty()) {
            return null;
        }
        MotorField found = unique(fields, field -> matchesExactly(field, exact, false));
        if (found != null) {
            return found;
        }
        found = unique(fields, field -> matchesExactly(field, loose, true));
        if (found != null) {
            return found;
        }
        return unique(fields, field -> containsEitherWay(field, loose));
    }

    private MotorField unique(List<MotorField> fields, java.util.function.Predicate<MotorField> test) {
        MotorField found = null;
        for (MotorField field : fields) {
            if (!test.test(field)) {
                continue;
            }
            if (found != null) {
                return null;                        // 候选不唯一 → 不确定
            }
            found = field;
        }
        return found;
    }

    private boolean matchesExactly(MotorField field, String normalizedName, boolean dropConditions) {
        for (String candidate : namesOf(field)) {
            String text = normName(candidate, dropConditions);
            if (!text.isEmpty() && text.equals(normalizedName)) {
                return true;
            }
        }
        return false;
    }

    private boolean containsEitherWay(MotorField field, String normalizedName) {
        for (String candidate : namesOf(field)) {
            String text = normName(candidate, true);
            if (text.length() < 2 || normalizedName.length() < 2) {
                continue;
            }
            if (containsLoose(text, normalizedName) || containsLoose(normalizedName, text)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 包含匹配：长别名按子串算，<b>短 ASCII 别名必须落在词边界上</b>。
     *
     * <p>踩过的坑（AKM200 水冷表）：{@code Ip} 是 {@code dissi<b>p</b>ation} 的子串，
     * {@code Ic} 是 {@code magnet<b>ic</b> period} 的子串。于是「Thermal Dissipation Constant」
     * 同时命中 {@code kth} 和 {@code iPeak}、「Magnetic Period」同时命中 {@code polePitch} 和
     * {@code iCont}，{@link #unique} 见多候选就返回 {@code null}（宁可跳过不猜），
     * 两个参数整行丢失 —— 表现为「这台水冷电机只缺热耗散常数、还多缺电磁周期」。
     *
     * <p>长度 ≥ 4 的别名仍按子串处理：{@code thermal dissipation} 命中
     * {@code thermal dissipation constant} 是正常的长名包含，拦掉反而会丢字段。
     */
    private static boolean containsLoose(String haystack, String needle) {
        if (needle.length() >= 4 || !ASCII.matcher(needle).matches()) {
            return haystack.contains(needle);
        }
        int from = 0;
        while (true) {
            int at = haystack.indexOf(needle, from);
            if (at < 0) {
                return false;
            }
            int end = at + needle.length();
            boolean leftFree = at == 0 || !isAsciiWord(haystack.charAt(at - 1));
            boolean rightFree = end == haystack.length() || !isAsciiWord(haystack.charAt(end));
            if (leftFree && rightFree) {
                return true;
            }
            from = at + 1;
        }
    }

    private static boolean isAsciiWord(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9');
    }

    private List<String> namesOf(MotorField field) {
        List<String> names = new ArrayList<>(field.aliases());
        names.add(field.label());
        return names;
    }

    /** 归一化：去括号补充说明、{@code ({cool})}、{@code ±10%}、空白与中点，转小写。 */
    static String normName(String raw) {
        return normName(raw, false);
    }

    private static String normName(String raw, boolean dropConditions) {
        if (raw == null) {
            return "";
        }
        String s = raw.replace("({cool})", "")
                .replaceAll("\\([^)]*\\)", "")
                .replaceAll("（[^）]*）", "")
                .replaceAll("±\\s*[0-9.]+\\s*%?", "");
        if (dropConditions) {
            // 「@100°C」「@25°C」「@peak」是测试条件，不是参数身份；Ωmax 两行靠 @峰值/@持续 区分，故此处不去掉
            s = s.replaceAll("@\\s*(?:\\d+\\s*[°℃]?\\s*[CF]?|\\s*25\\s*[°℃]\\s*[CF])", "");
        }
        s = s.replaceAll("[\\s·]", "").toLowerCase(java.util.Locale.ROOT);
        return s.strip();
    }

    private static String normSymbol(String symbol) {
        if (symbol == null) {
            return "";
        }
        String s = symbol.replaceAll("[\\s·]", "").strip();
        if (s.equals("-") || s.equals("—") || s.equals("一")) {
            return "";                              // 占位横线不是符号
        }
        return s.toUpperCase(java.util.Locale.ROOT);
    }

    // ---------------------------------------------------------------- 值

    static BigDecimal parseNumber(String raw) {
        if (raw == null) {
            return null;
        }
        String s = raw.replace(",", "").replace("，", "").strip();
        if (s.isEmpty()) {
            return null;
        }
        Matcher matcher = NUMBER.matcher(s);
        if (!matcher.find()) {
            return null;                            // 「-」「N/A」「待定」这类没有数值
        }
        try {
            return new BigDecimal(matcher.group());
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    /**
     * 样册行单位 → 内部单位的换算系数。
     *
     * <p>2026-11-20（CAL-25）：模板里「录入单位 = 内部单位」已无一例外（音圈质量也按 kg），
     * 换算**只在入库这一层按样册自己写的单位做**——样册「线圈质量 mcoil g 72.9」要落成
     * {@code 0.0729 kg}，而「mcn kg 1.1」原样落 {@code 1.1 kg}。此前靠模板 {@code factor}
     * 把 g 当录入单位，导致参数表出现「值 0.0729 / 单位 g」这种半截换算（AVM20-HF-35）。
     *
     * <p>目前只涉及质量（{@code g → kg}）；其余字段的样册单位与内部单位本就一致，返回 1。
     * 样册没写单位时按内部单位理解——不猜。
     */
    private static BigDecimal scaleOf(MotorField field, String unit) {
        String internal = field.unit() == null ? "" : field.unit().strip().toLowerCase(java.util.Locale.ROOT);
        String actual = unit == null ? "" : unit.strip().toLowerCase(java.util.Locale.ROOT);
        if (internal.equals("kg") && (actual.equals("g") || actual.equals("gram") || actual.equals("克"))) {
            return GRAM_TO_KG;
        }
        if (internal.equals("g") && actual.equals("kg")) {
            return KILOGRAM_TO_GRAM;               // 兜底：模板将来若真按 g 定义
        }
        return BigDecimal.ONE;
    }

    // ---------------------------------------------------------------- 主键

    /**
     * 型号列 → {型号名, 配置名}：**型号名原样返回，配置名恒为「默认」**。
     *
     * <p>口径（第 65 轮裁定）：入库后的型号名必须与「参数汇总」里那一列**逐字一致**，
     * 用户拿着 Excel 去电机库查型号不能对不上。所以 Excel 写 {@code AXD120-71-S}
     * （样册的串联/并联已由 {@link CatalogWorkbookWriter} 压成 {@code -S}/{@code -P}），
     * 库里就存 {@code AXD120-71-S}，不再拆成 {@code AXD120-71} + 串联。
     *
     * <p>代价：同一机座的串联/并联在库里是两个独立型号（各自配置「默认」），
     * 不再表现为「一个型号 × 两种配置」。
     */
    static String[] keyOf(String model) {
        return new String[]{model == null ? "" : model.strip(), CONFIG_DEFAULT};
    }

    // ---------------------------------------------------------------- 文案

    private static String labelOf(MotorTypeTemplate template, String key) {
        return template.field(key).map(MotorField::label)
                // {cool} 是录入表单的冷却占位符，提示语里没有冷却上下文，去掉并压掉多余空白
                .map(label -> label.replace("({cool})", "").replace("{cool}", "")
                        .replaceAll("\\s+", " ").strip())
                .orElse(key);
    }

    private static String describe(CatalogSummarySheet.SheetRow row) {
        String name = row.name() == null || row.name().isBlank() ? "（无名称）" : row.name();
        String symbol = row.symbol() == null || row.symbol().isBlank() ? "" : "[" + row.symbol() + "]";
        return name + symbol;
    }
}
