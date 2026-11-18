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
 * <p>支持两种排版（Q1 裁定）：
 * <ul>
 *   <li>单配置行式：每行「参数名 + 值」，以型号名行切分段落，一段一草稿；</li>
 *   <li>多列对照表：表头为配置名（默认/串联/并联），每行「参数名 + 多列值」，
 *       表头每列一条草稿，表头识别失败降级 EMPTY。</li>
 * </ul>
 *
 * <p>启发式：字段匹配取最长命中；型号名按「型号: XXX」或型号代码（如 ADR110-B136）识别。
 * 数值按 K2 {@code factor} 换算为内部单位后入草稿 spec，原始值留 rawFields。
 */
@Component
public class PageRecognizer {

    private static final Pattern NUMBER = Pattern.compile("-?\\d[\\d,]*(?:\\.\\d+)?");
    private static final Pattern MODEL_PREFIX = Pattern.compile("(?:型号|Model|model)[:：\\s]*([A-Za-z0-9][A-Za-z0-9\\-/]*)");
    private static final Pattern MODEL_CODE = Pattern.compile("\\b[A-Z]{2,}\\d*[A-Z]*-?[A-Z0-9]+(?:-[A-Z0-9]+)*\\b");

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

        // 1. 电机类型判定：统计各类型命中字段数
        MotorType type = detectType(lines);
        if (type == null) {
            return new Result(PageStatus.EMPTY, "未识别到任何已知字段", List.of());
        }

        // 2. 多列表头检测
        Optional<Header> headerOpt = detectHeader(lines, type);

        // 3. 解析为段落（每段一个型号；多列时每列一个同型号段落）
        List<Segment> segments;
        if (headerOpt.isPresent()) {
            segments = parseMulti(lines, headerOpt.get(), type);
        } else {
            segments = parseSingle(lines, type);
        }

        if (segments.isEmpty()) {
            return new Result(PageStatus.EMPTY, "识别到类型 " + type.code() + " 但未切出型号段", List.of());
        }

        // 4. 组装草稿
        String sourcePage = "第 " + pageNumber + " 页";
        List<Draft> drafts = new ArrayList<>();
        int draftSeq = 1;
        for (Segment seg : segments) {
            drafts.add(buildDraft(seg, type, fileId, sourcePage, sourceDocName, draftSeq++));
        }
        return new Result(PageStatus.RECOGNIZED, null, drafts);
    }

    // ----------------------------------------------------------- 类型判定

    private MotorType detectType(List<String> lines) {
        Map<MotorType, Integer> counts = new HashMap<>();
        for (String line : lines) {
            FieldDictionary.Match m = dictionary.match(line);
            if (m != null) {
                for (FieldDictionary.Entry e : m.entries()) {
                    counts.merge(e.type(), 1, Integer::sum);
                }
            }
        }
        MotorType best = null;
        int bestCount = 0;
        for (Map.Entry<MotorType, Integer> e : counts.entrySet()) {
            if (e.getValue() > bestCount || (e.getValue() == bestCount && best == null)) {
                best = e.getKey();
                bestCount = e.getValue();
            }
        }
        return bestCount >= 2 ? best : null;   // 至少命中 2 个字段才认定类型
    }

    // ----------------------------------------------------------- 多列表头

    private Optional<Header> detectHeader(List<String> lines, MotorType type) {
        MotorTypeTemplate tpl = MotorCatalogTemplates.of(type);
        List<String> configs = tpl.configs();
        for (int i = 0; i < lines.size(); i++) {
            String[] tokens = lines.get(i).split("\\s{2,}|\t");   // 多列靠 2+ 空格或 Tab 分隔
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

    // ----------------------------------------------------------- 单配置解析

    private List<Segment> parseSingle(List<String> lines, MotorType type) {
        List<Segment> segments = new ArrayList<>();
        Segment current = null;
        for (String line : lines) {
            String model = detectModelName(line);
            if (model != null) {
                if (current != null && !current.spec.isEmpty()) {
                    segments.add(current);
                }
                current = new Segment(model, defaultConfig(type));
                continue;
            }
            FieldDictionary.Match m = dictionary.match(line);
            if (m == null) continue;
            FieldDictionary.Entry e = pickEntry(m.entries(), type);
            if (e == null) continue;
            Double value = extractNumberAfter(line, m.matchedText());
            if (value == null) continue;
            if (current == null) {
                current = new Segment("", defaultConfig(type));   // 无型号名行也收，提交前用户补
            }
            current.put(e, value, line);
        }
        if (current != null && !current.spec.isEmpty()) {
            segments.add(current);
        }
        return segments;
    }

    // ----------------------------------------------------------- 多列解析

    private List<Segment> parseMulti(List<String> lines, Header header, MotorType type) {
        // 每列一个段落；型号名随遇到更新（应用到全部列）
        List<Segment> columns = new ArrayList<>();
        for (String config : header.configs) {
            columns.add(new Segment("", config));
        }
        String currentModel = "";
        // 扫描全文（跳过表头行本身），型号名可能在表头之前
        for (int i = 0; i < lines.size(); i++) {
            if (i == header.lineIndex) continue;
            String line = lines.get(i);
            String model = detectModelName(line);
            if (model != null) {
                currentModel = model;
                for (Segment seg : columns) seg.modelName = model;
                continue;
            }
            // 行切分：参数名 + 多个数值
            String[] cells = line.split("\\s{2,}|\t");
            if (cells.length < 2) {
                // 退化：整行找 label + 多数值
                cells = line.split("\\s+");
            }
            // 第一个 cell 当作参数名候选，其余解析为数值
            String first = cells[0].trim();
            FieldDictionary.Match m = dictionary.match(first);
            if (m == null) continue;
            FieldDictionary.Entry e = pickEntry(m.entries(), type);
            if (e == null) continue;
            List<Double> nums = new ArrayList<>();
            for (int c = 1; c < cells.length; c++) {
                Double v = parseNumber(cells[c]);
                if (v != null) nums.add(v);
            }
            if (nums.isEmpty()) continue;
            for (int col = 0; col < columns.size() && col < nums.size(); col++) {
                columns.get(col).put(e, nums.get(col), line);
            }
        }
        // 只保留有 spec 的列；型号名套用
        List<Segment> result = new ArrayList<>();
        for (Segment seg : columns) {
            if (!seg.spec.isEmpty()) {
                if (seg.modelName.isEmpty()) seg.modelName = currentModel;
                result.add(seg);
            }
        }
        return result;
    }

    // ----------------------------------------------------------- 草稿组装

    private Draft buildDraft(Segment seg, MotorType type, String pageId, String sourcePage,
                             String sourceDocName, int seq) {
        MotorTypeTemplate tpl = MotorCatalogTemplates.of(type);
        Map<String, Object> spec = new LinkedHashMap<>();
        Map<String, Draft.RawField> rawFields = new LinkedHashMap<>();
        for (Map.Entry<String, Segment.FieldValue> e : seg.spec.entrySet()) {
            FieldDictionary.Entry entry = e.getValue().entry();
            double internal = e.getValue().value() * entry.factor();
            spec.put(e.getKey(), internal);
            rawFields.put(e.getKey(), new Draft.RawField(
                    formatRaw(e.getValue().value()), entry.unit(), e.getValue().line()));
        }
        int required = tpl.requiredKeys().size();
        int matchedRequired = (int) tpl.requiredKeys().stream().filter(spec::containsKey).count();
        int mapped = spec.size();
        int requiredMissing = required - matchedRequired;
        double confidence = mapped + requiredMissing == 0 ? 0 : (double) mapped / (mapped + requiredMissing);

        MotorUpsertRequest request = new MotorUpsertRequest(
                seg.modelName,
                seg.configName,
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
        return new Draft(
                "d" + seq, pageId, sourcePage, confidence, request, rawFields, List.of(), false);
    }

    // ----------------------------------------------------------- 辅助

    private FieldDictionary.Entry pickEntry(List<FieldDictionary.Entry> entries, MotorType type) {
        for (FieldDictionary.Entry e : entries) {
            if (e.type() == type) return e;
        }
        return entries.isEmpty() ? null : entries.get(0);
    }

    private Double extractNumberAfter(String line, String matchedText) {
        // 样册行格式为「标签 [测试条件如 @25°C / ±10%] 值 [单位]」，
        // 测试条件里可能含数字（25/10），故取行内最后一个数值作为字段值。
        Matcher m = NUMBER.matcher(line);
        Double last = null;
        while (m.find()) {
            Double v = parseNumber(m.group());
            if (v != null) last = v;
        }
        return last;
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

    static String detectModelName(String line) {
        Matcher mp = MODEL_PREFIX.matcher(line);
        if (mp.find()) {
            String name = mp.group(1);
            if (name.length() >= 3) return name;
        }
        Matcher mc = MODEL_CODE.matcher(line);
        while (mc.find()) {
            String candidate = mc.group();
            // 排除纯数值或过短
            if (candidate.length() >= 4 && candidate.contains("-")) {
                return candidate;
            }
        }
        return null;
    }

    private static String defaultConfig(MotorType type) {
        List<String> configs = MotorCatalogTemplates.of(type).configs();
        return configs.isEmpty() ? "默认" : configs.get(0);
    }

    private static String formatRaw(double v) {
        if (v == Math.floor(v)) {
            return String.valueOf((long) v);
        }
        return String.valueOf(v);
    }

    // ----------------------------------------------------------- 内部结构

    /** 解析结果。 */
    public record Result(PageStatus status, String message, List<Draft> drafts) {
    }

    /** 多列表头。 */
    private record Header(List<String> configs, int lineIndex) {
    }

    /** 解析段落：一个型号 + 一个配置 + 已填字段。 */
    private static class Segment {
        String modelName;
        final String configName;
        final Map<String, FieldValue> spec = new LinkedHashMap<>();

        Segment(String modelName, String configName) {
            this.modelName = modelName;
            this.configName = configName;
        }

        void put(FieldDictionary.Entry entry, double value, String line) {
            spec.put(entry.key(), new FieldValue(entry, value, line));
        }

        record FieldValue(FieldDictionary.Entry entry, double value, String line) {
        }
    }
}
