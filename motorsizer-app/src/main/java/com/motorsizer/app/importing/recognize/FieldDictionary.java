package com.motorsizer.app.importing.recognize;

import com.motorsizer.domain.catalog.MotorCatalogTemplates;
import com.motorsizer.domain.catalog.MotorField;
import com.motorsizer.domain.catalog.MotorType;
import com.motorsizer.domain.catalog.MotorTypeTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * K2 字段字典（06 §6）：把 label 与 aliases（Q2）归一化为匹配键 → key，
 * 供样册文本行匹配。同一 key 在多类型模板中语义一致（C-B 不变量）。
 *
 * <p>归一化规则：去空白、去 {@code ({cool})} 占位符、去 {@code @xxx} 测试条件、
 * 去 {@code ±xxx}、去括号内补充说明，便于样册变体命中。
 */
@Component
public class FieldDictionary {

    /** 单条匹配项：归一化文本 → (类型, key)。 */
    public record Entry(MotorType type, String key, String unit, double factor, boolean required, String symbol) {
    }

    private final List<Entry> allEntries = new ArrayList<>();
    /** 归一化文本 → 命中的 entries（同一文本可能在多类型命中）。 */
    private final Map<String, List<Entry>> index = new HashMap<>();
    /** 符号（如 Tcn/Kt/R25）→ entries。符号是样册参数表「符号」列的精确键。 */
    private final Map<String, List<Entry>> symbolIndex = new HashMap<>();

    public FieldDictionary() {
        for (MotorType type : MotorCatalogTemplates.typeOrder()) {
            MotorTypeTemplate tpl = MotorCatalogTemplates.of(type);
            for (MotorField field : tpl.fields()) {
                Entry entry = new Entry(type, field.key(), field.unit(),
                        field.factor().doubleValue(), field.required(), field.symbol());
                register(normalize(field.label()), entry);
                for (String alias : field.aliases()) {
                    register(normalize(alias), entry);
                }
                if (field.symbol() != null && !field.symbol().isBlank()) {
                    symbolIndex.computeIfAbsent(field.symbol(), k -> new ArrayList<>()).add(entry);
                }
            }
        }
    }

    private void register(String normalized, Entry entry) {
        if (normalized.isBlank()) {
            return;
        }
        allEntries.add(entry);
        index.computeIfAbsent(normalized, k -> new ArrayList<>()).add(entry);
    }

    /** 按符号精确匹配字段（符号是样册「符号」列，优先级最高）。找不到返回 null。 */
    public List<Entry> matchSymbol(String symbol) {
        if (symbol == null) return null;
        return symbolIndex.get(symbol);
    }

    /** 在一行文本里查找命中的字段：返回 (归一化匹配文本, entry) 中匹配文本最长者。 */
    public Match match(String line) {
        String normLine = normalize(line);
        Match best = null;
        for (Map.Entry<String, List<Entry>> e : index.entrySet()) {
            String key = e.getKey();
            if (key.isEmpty()) continue;
            int pos = normLine.indexOf(key);
            if (pos >= 0) {
                // 选最长匹配文本（避免「电阻」误命中含「相间电阻」的行）
                if (best == null || key.length() > best.matchedText.length()) {
                    best = new Match(key, e.getValue(), pos);
                }
            }
        }
        return best;
    }

    public List<Entry> entries() {
        return allEntries;
    }

    /** 归一化：小写拉丁、去空白与常见修饰。保留 @ 测试条件（峰值/持续区分依赖它）。 */
    static String normalize(String text) {
        if (text == null) return "";
        String s = text;
        s = s.replaceAll("\\(\\{cool\\}\\)", "");     // ({cool})
        s = s.replaceAll("\\([^)]*\\)", "");           // 任意括号补充
        s = s.replaceAll("±[^ ]+", "");                // ±10%
        s = s.replaceAll("[\\s·]", "");                // 空白与中点（保留 @ 用于峰值/持续区分）
        s = s.toLowerCase();
        return s.trim();
    }

    public record Match(String matchedText, List<Entry> entries, int position) {
    }
}
