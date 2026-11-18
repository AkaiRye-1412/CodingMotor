package com.motorsizer.app.importing.recognize;

import com.motorsizer.app.dto.MotorUpsertRequest;
import com.motorsizer.app.importing.Draft;
import com.motorsizer.app.importing.DraftIssue;
import com.motorsizer.app.importing.PageStatus;
import com.motorsizer.domain.catalog.MotorCatalogTemplates;
import com.motorsizer.domain.catalog.MotorType;
import com.motorsizer.domain.catalog.MotorTypeTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 页面解析器（06 §5）：文本行 → 草稿。
 *
 * <p>识别策略（符号优先，见 06 §6 与 2026-11-19 用户反馈）：
 * <ol>
 *   <li><b>符号列匹配</b>：样册参数表每行含「符号」列（Tcn/Tpk/Kt/Ke/R25/Icn/Ipk/…），
 *       符号稳定唯一，是字段映射的一等键，优先级高于中文/英文别名。</li>
 *   <li><b>只抓必填参数</b>：非 required 字段忽略，避免范围值/角标值/编码器参数等
 *       噪音干扰（用户裁定「主要抓必填参数，其他忽略」）。</li>
 *   <li><b>多列对齐取值</b>：检测「串联/并联」等配置列头，按列取对应配置的值，
 *       不再「取行内最后一个数」（旧策略会误取范围上限/角标数字）。</li>
 *   <li><b>型号切分</b>：型号名由「型号名行」唯一确定（如「AXD120-71」整行），
 *       不把同一行里的多个数字片段误判为多个型号。</li>
 * </ol>
 */
@Component
public class PageRecognizer {

    private static final Pattern NUMBER = Pattern.compile("-?\\d[\\d,]*(?:\\.\\d+)?(?:[eE][+-]?\\d+)?");
    private static final Pattern MODEL_PREFIX = Pattern.compile("(?:型号|Model|model)[:：\\s]*([A-Za-z0-9][A-Za-z0-9\\-_\\.]*)");

    private final FieldDictionary dictionary;

    public PageRecognizer(FieldDictionary dictionary) {
        this.dictionary = dictionary;
    }

    /** 解析一页。返回页面状态与草稿列表（草稿可能为空）。 */
    public Result recognize(PageText text, String fileId, int pageNumber, String sourceDocName) {
        if (text.lineCount() == 0) {
            return new Result(PageStatus.EMPTY, "未提取到文本（可能为扫描页，需 OCR）", List.of());
        }
        List<String> lines = text.lines();

        // 1. 电机类型判定：按符号与别名统计各类型命中的必填字段数
        MotorType type = detectType(lines);
        if (type == null) {
            return new Result(PageStatus.EMPTY, "未识别到任何已知字段", List.of());
        }

        MotorTypeTemplate tpl = MotorCatalogTemplates.of(type);

        // 2. 检测配置列头（多列对照表：串联/并联/默认 等）
        Optional<Header> headerOpt = detectHeader(lines, tpl.configs());

        // 3. 逐行解析：符号列 → 字段；数值按列取
        //    记录字段值（可能多列 → 多配置），与型号名行
        Map<String, Double> values = new LinkedHashMap<>();   // key -> 首个（默认）配置值
        Map<String, List<Double>> columnValues = new LinkedHashMap<>(); // key -> 各列值
        String modelName = "";
        Map<String, String> rawByKey = new LinkedHashMap<>();
        // 逐行扫描
        for (String line : lines) {
            String model = detectModelName(line);
            if (model != null && !model.isEmpty()) {
                modelName = model;
                continue;
            }
            // 符号匹配：在行内找符号 token，返回 entry + 符号结束位置
            SymbolHit hit = matchRequiredBySymbolOrLabel(line, type);
            if (hit == null) continue;
            // 从符号之后取数值（避免把符号里的数字如 R25 的 25、@25°C 的 25 当值）
            List<Double> nums = extractNumbersAfter(line, hit.symbolEnd());
            if (nums.isEmpty()) continue;
            // 单值 → 唯一配置；多值 → 按列
            values.putIfAbsent(hit.entry().key(), nums.get(0));
            columnValues.put(hit.entry().key(), nums);
            rawByKey.put(hit.entry().key(), line);
        }

        if (values.isEmpty()) {
            return new Result(PageStatus.EMPTY, "识别到类型 " + type.code() + " 但未提取到必填参数值", List.of());
        }

        // 4. 按配置列拆草稿
        List<Draft> drafts = buildDrafts(type, tpl, modelName, values, columnValues, rawByKey,
                headerOpt.orElse(null), fileId, pageNumber, sourceDocName);
        return new Result(PageStatus.RECOGNIZED, null, drafts);
    }

    // ----------------------------------------------------------- 类型判定

    private MotorType detectType(List<String> lines) {
        Map<MotorType, Integer> counts = new HashMap<>();
        // 别名/标签匹配（权重 1）
        for (String line : lines) {
            FieldDictionary.Match m = dictionary.match(line);
            if (m != null) {
                for (FieldDictionary.Entry entry : m.entries()) {
                    counts.merge(entry.type(), 1, Integer::sum);
                }
            }
        }
        // 符号匹配（权重 2，符号比别名更可信）
        for (String line : lines) {
            for (String sym : extractSymbols(line)) {
                List<FieldDictionary.Entry> entries = dictionary.matchSymbol(sym);
                if (entries != null) {
                    for (FieldDictionary.Entry e : entries) {
                        counts.merge(e.type(), 2, Integer::sum);
                    }
                }
            }
        }
        // 按 typeOrder 优先级，严格大于才更新（平局取直线/力矩/音圈顺序）
        MotorType best = null;
        int bestCount = 0;
        for (MotorType type : MotorCatalogTemplates.typeOrder()) {
            Integer c = counts.get(type);
            if (c != null && c > bestCount) {
                best = type;
                bestCount = c;
            }
        }
        return bestCount >= 1 ? best : null;
    }

    // ----------------------------------------------------------- 配置列头

    private Optional<Header> detectHeader(List<String> lines, List<String> configs) {
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            String[] tokens = line.split("\\s{2,}|\t");
            if (tokens.length < 2) {
                tokens = line.split("\\s+");
            }
            List<String> hit = new ArrayList<>();
            for (String t : tokens) {
                String trimmed = t.trim();
                if (configs.contains(trimmed)) {
                    hit.add(trimmed);
                }
            }
            if (hit.size() >= 2) {
                return Optional.of(new Header(hit, i));
            }
        }
        return Optional.empty();
    }

    // ----------------------------------------------------------- 字段匹配（符号优先）

    /** 匹配结果：entry + 符号 token 在原文中的结束位置（用于从符号后取值）。 */
    private record SymbolHit(FieldDictionary.Entry entry, int symbolEnd) {
    }

    /** 在行内按符号或别名匹配一个「必填」字段的 entry。返回 null 表示未命中。 */
    private SymbolHit matchRequiredBySymbolOrLabel(String line, MotorType type) {
        // 1) 符号精确匹配（一等键）：定位符号 token 及其结束位置
        Matcher sm = Pattern.compile("\\bΩ?[A-Za-z][A-Za-z0-9_]*\\b").matcher(line);
        while (sm.find()) {
            String tok = sm.group();
            List<FieldDictionary.Entry> entries = dictionary.matchSymbol(tok);
            if (entries != null) {
                FieldDictionary.Entry e = pickEntry(entries, type);
                if (e != null && e.required()) {
                    return new SymbolHit(e, sm.end());
                }
            }
        }
        // 2) 别名/标签兜底（仅必填）：取匹配文本之后
        FieldDictionary.Match m = dictionary.match(line);
        if (m != null) {
            FieldDictionary.Entry e = pickEntry(m.entries(), type);
            if (e != null && e.required()) {
                // 用归一化匹配文本在原文里的近似位置（忽略空白差异，用原始匹配文本长度近似）
                int end = approximateEnd(line, m.matchedText());
                return new SymbolHit(e, end);
            }
        }
        return null;
    }

    /** 在原文里近似定位归一化匹配文本的结束位置。 */
    private int approximateEnd(String line, String normalizedMatched) {
        // 归一化去掉了空白/括号，难以精确映射；退化为整行扫描后取第一个别名关键词的结束
        // 简化：返回 0 表示从行首取数（兜底路径精度较低，但符号路径是主路径）
        return 0;
    }

    private FieldDictionary.Entry pickEntry(List<FieldDictionary.Entry> entries, MotorType type) {
        for (FieldDictionary.Entry e : entries) {
            if (e.type() == type) return e;
        }
        return entries.isEmpty() ? null : entries.get(0);
    }

    /** 从一行里提取「符号列」的候选符号（如 Tcn、Kt、R25、Ωmax、2p）。 */
    private List<String> extractSymbols(String line) {
        List<String> result = new ArrayList<>();
        // 符号模式：字母+数字（如 Tcn/Icn/R25/Ipk/Pcn/Tmax/Kth/Ubus/2p/Te/Ke/Km/Kt/L）或 Ωmax
        Matcher m = Pattern.compile("\\bΩ?[A-Za-z][A-Za-z0-9_]*\\b").matcher(line);
        while (m.find()) {
            String tok = m.group();
            // 过滤明显的普通英文单词（首字母大写但全小写词如 "Series" 不算符号）
            if (dictionary.matchSymbol(tok) != null) {
                result.add(tok);
            }
        }
        return result;
    }

    // ----------------------------------------------------------- 数值提取（按列）

    /** 从指定位置之后提取行内所有「字段值」数值，跳过 @测试条件 / ±容差 里的数字。 */
    private List<Double> extractNumbersAfter(String line, int fromIndex) {
        String tail = fromIndex > 0 && fromIndex < line.length() ? line.substring(fromIndex) : line;
        // 去掉 @数字 测试条件（如 @25°C、@100℃）与 ±数字 容差（如 ±10%、±3），避免误取
        String cleaned = tail
                .replaceAll("@\\s*-?\\d[\\d,]*(?:\\.\\d+)?", " ")
                .replaceAll("±\\s*-?\\d[\\d,]*(?:\\.\\d+)?", " ");
        Matcher m = NUMBER.matcher(cleaned);
        List<Double> nums = new ArrayList<>();
        while (m.find()) {
            Double v = parseNumber(m.group());
            if (v != null) nums.add(v);
        }
        return nums;
    }

    static Double parseNumber(String text) {
        if (text == null) return null;
        Matcher m = NUMBER.matcher(text);
        if (!m.find()) return null;
        String cleaned = m.group().replace(",", "");
        try {
            return Double.parseDouble(cleaned);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    // ----------------------------------------------------------- 型号名

    static String detectModelName(String line) {
        Matcher mp = MODEL_PREFIX.matcher(line);
        if (mp.find()) {
            String name = mp.group(1);
            if (name.length() >= 3) return name;
        }
        // 无「型号:」前缀时，检测独立的型号代码行（整行几乎只有型号，如「AXD120-71」）
        String trimmed = line.trim();
        if (trimmed.matches("[A-Za-z][A-Za-z0-9\\-_]{2,30}")) {
            // 整行是一个型号 token（不含空格、不含中文、不含参数值）
            return trimmed;
        }
        return null;
    }

    // ----------------------------------------------------------- 草稿组装

    private List<Draft> buildDrafts(MotorType type, MotorTypeTemplate tpl, String modelName,
                                    Map<String, Double> values,
                                    Map<String, List<Double>> columnValues,
                                    Map<String, String> rawByKey,
                                    Header header,
                                    String fileId, int pageNumber, String sourceDocName) {
        String sourcePage = "第 " + pageNumber + " 页";
        List<String> configs = (header != null && !header.configs.isEmpty())
                ? header.configs
                : (tpl.configs().isEmpty() ? List.of("默认") : List.of(tpl.configs().get(0)));

        // 若多列且列值数量 >= 配置数，按列拆；否则单配置
        List<Draft> drafts = new ArrayList<>();
        if (header != null && configs.size() > 1) {
            for (int col = 0; col < configs.size(); col++) {
                Map<String, Object> spec = new LinkedHashMap<>();
                Map<String, Draft.RawField> rawFields = new LinkedHashMap<>();
                for (Map.Entry<String, List<Double>> e : columnValues.entrySet()) {
                    String key = e.getKey();
                    List<Double> nums = e.getValue();
                    Double v = col < nums.size() ? nums.get(col) : null;
                    if (v == null) continue;
                    FieldDictionary.Entry entry = entryFor(type, key);
                    double internal = v * (entry != null ? entry.factor() : 1.0);
                    spec.put(key, internal);
                    String unit = entry != null ? entry.unit() : "";
                    rawFields.put(key, new Draft.RawField(formatRaw(v), unit, rawByKey.get(key)));
                }
                drafts.add(makeDraft(type, tpl, modelName, configs.get(col), spec, rawFields,
                        fileId, sourcePage, sourceDocName, drafts.size() + 1));
            }
        } else {
            Map<String, Object> spec = new LinkedHashMap<>();
            Map<String, Draft.RawField> rawFields = new LinkedHashMap<>();
            for (Map.Entry<String, Double> e : values.entrySet()) {
                FieldDictionary.Entry entry = entryFor(type, e.getKey());
                double internal = e.getValue() * (entry != null ? entry.factor() : 1.0);
                spec.put(e.getKey(), internal);
                String unit = entry != null ? entry.unit() : "";
                rawFields.put(e.getKey(), new Draft.RawField(formatRaw(e.getValue()), unit, rawByKey.get(e.getKey())));
            }
            String config = configs.isEmpty() ? "默认" : configs.get(0);
            drafts.add(makeDraft(type, tpl, modelName, config, spec, rawFields,
                    fileId, sourcePage, sourceDocName, 1));
        }
        return drafts;
    }

    private FieldDictionary.Entry entryFor(MotorType type, String key) {
        for (FieldDictionary.Entry e : dictionary.entries()) {
            if (e.type() == type && e.key().equals(key)) return e;
        }
        return null;
    }

    private Draft makeDraft(MotorType type, MotorTypeTemplate tpl, String modelName, String configName,
                            Map<String, Object> spec, Map<String, Draft.RawField> rawFields,
                            String fileId, String sourcePage, String sourceDocName, int seq) {
        int required = tpl.requiredKeys().size();
        int matchedRequired = (int) tpl.requiredKeys().stream().filter(spec::containsKey).count();
        int mapped = spec.size();
        int requiredMissing = required - matchedRequired;
        double confidence = mapped + requiredMissing == 0 ? 0 : (double) mapped / (mapped + requiredMissing);

        MotorUpsertRequest request = new MotorUpsertRequest(
                modelName,
                configName,
                type.code(),
                tpl.structures().isEmpty() ? "" : tpl.structures().get(0),
                tpl.coolings().isEmpty() ? "" : tpl.coolings().get(0),
                tpl.bores().isEmpty() ? null : tpl.bores().get(0),
                null,                                  // manufacturer
                null,                                  // series
                sourceDocName,
                sourcePage,
                "识别草稿",
                LocalDate.now(),
                null,
                spec);
        return new Draft("d" + seq, fileId, sourcePage, confidence, request, rawFields, List.of(), false);
    }

    // ----------------------------------------------------------- 辅助

    private static String formatRaw(double v) {
        if (v == Math.floor(v) && !Double.isInfinite(v) && Math.abs(v) < 1e15) {
            return String.valueOf((long) v);
        }
        return String.valueOf(v);
    }

    // ----------------------------------------------------------- 内部结构

    /** 解析结果。 */
    public record Result(PageStatus status, String message, List<Draft> drafts) {
    }

    /** 配置列头。 */
    private record Header(List<String> configs, int lineIndex) {
    }
}
