package com.motorsizer.app.importing.commit;

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

    /** 未带接法的型号（以及全部型号，见 {@link #keyOf}）用的配置名。 */
    private static final String CONFIG_DEFAULT = "默认";

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

        List<String> missing = template.requiredKeys().stream()
                .filter(key -> !spec.containsKey(key))
                .map(key -> labelOf(template, key))
                .toList();
        if (!unmapped.isEmpty()) {
            List<String> shown = unmapped.stream().limit(5).toList();
            notes.add("未映射的参数：" + String.join("、", shown) + (unmapped.size() > 5 ? " 等" : ""));
        }

        String[] key = keyOf(model);
        return new MappedMotor(
                key[0], key[1], type,
                template.structures().isEmpty() ? "" : template.structures().get(0),
                template.coolings().isEmpty() ? "" : template.coolings().get(0),
                spec, missing, notes);
    }

    // ---------------------------------------------------------------- 类型

    /** 命中行数最多的模板胜出；并列时按 A1 顺序（直线 → 力矩 → 音圈）。 */
    private MotorType detectType(List<CatalogSummarySheet.SheetRow> rows) {
        MotorType best = MotorType.LINEAR;
        int bestHits = -1;
        for (MotorType type : MotorCatalogTemplates.typeOrder()) {
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
        }
        return pickByName(fields, row.name());
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
            if (text.contains(normalizedName) || normalizedName.contains(text)) {
                return true;
            }
        }
        return false;
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
     * 录入单位 → 内部单位的换算系数（K2 {@code factor}）。
     *
     * <p>只有音圈质量是 {@code g → kg}（factor 0.001）。样册直接给 kg 时不再乘，避免差 1000 倍。
     */
    private static BigDecimal scaleOf(MotorField field, String unit) {
        BigDecimal factor = field.factor() == null ? BigDecimal.ONE : field.factor();
        if (factor.compareTo(BigDecimal.ONE) == 0) {
            return BigDecimal.ONE;
        }
        String actual = unit == null ? "" : unit.strip().toLowerCase(java.util.Locale.ROOT);
        if (actual.isEmpty()) {
            return factor;                          // 没有单位列 → 按录入单位理解
        }
        String expected = field.unit() == null ? "" : field.unit().strip().toLowerCase(java.util.Locale.ROOT);
        return actual.equals(expected) ? factor : BigDecimal.ONE;
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
