package com.motorsizer.app.importing;

import com.motorsizer.app.dto.MotorUpsertRequest;
import com.motorsizer.domain.catalog.MotorCatalogTemplates;
import com.motorsizer.domain.catalog.MotorField;
import com.motorsizer.domain.catalog.MotorType;
import com.motorsizer.domain.catalog.MotorTypeTemplate;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;

/**
 * 样册识别 → Excel 导出（2026-11-19 用户方案：符号映射 → 生成 Excel 人工核对 → 回填）。
 *
 * <p>每个草稿一行，列 = 型号名 / 配置 / 类型 + 全部必填字段（列头用「符号|中文名」）。
 * 用户可在 Excel 里补全缺失值、修正识别错误，再回系统（后续版本支持上传回填）。
 */
@Component
public class ImportExcelExporter {

    /** 导出为一个 xlsx 字节数组。 */
    public byte[] export(List<Draft> drafts) throws IOException {
        try (Workbook wb = new XSSFWorkbook();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = wb.createSheet("识别草稿");

            // 列头
            Row header = sheet.createRow(0);
            int col = 0;
            header.createCell(col++).setCellValue("型号名");
            header.createCell(col++).setCellValue("配置");
            header.createCell(col++).setCellValue("类型");
            header.createCell(col++).setCellValue("置信度");
            header.createCell(col++).setCellValue("来源页");

            // 必填字段列：统一用「力矩」模板的必填集合（三类型并集取力矩为例，实际按每条草稿类型展开）
            // 为简单与可读性，列集合 = 三种类型必填字段的并集（去重，保序）
            List<String> requiredKeys = unionRequiredKeys();
            for (String key : requiredKeys) {
                header.createCell(col++).setCellValue(key);
            }
            int valueColStart = 5;   // 值列从第 6 列（0-based 5）开始

            // 数据行
            int rowIdx = 1;
            for (Draft draft : drafts) {
                MotorUpsertRequest r = draft.request();
                Row row = sheet.createRow(rowIdx++);
                int c = 0;
                row.createCell(c++).setCellValue(r.modelName() == null ? "" : r.modelName());
                row.createCell(c++).setCellValue(r.configName() == null ? "" : r.configName());
                row.createCell(c++).setCellValue(r.motorType() == null ? "" : r.motorType());
                row.createCell(c++).setCellValue(draft.confidence());
                row.createCell(c++).setCellValue(draft.sourcePage() == null ? "" : draft.sourcePage());

                // 必填字段值
                for (int i = 0; i < requiredKeys.size(); i++) {
                    Object v = r.spec() == null ? null : r.spec().get(requiredKeys.get(i));
                    Cell cell = row.createCell(valueColStart + i);
                    if (v instanceof Number n) {
                        cell.setCellValue(n.doubleValue());
                    } else if (v != null) {
                        cell.setCellValue(v.toString());
                    }
                }
            }

            // 列宽自适应（近似）
            for (int i = 0; i < col; i++) {
                sheet.setColumnWidth(i, 16 * 256);
            }

            wb.write(out);
            return out.toByteArray();
        }
    }

    /** 三类型必填字段 key 的并集（去重，按 typeOrder 保序）。 */
    private List<String> unionRequiredKeys() {
        java.util.LinkedHashSet<String> keys = new java.util.LinkedHashSet<>();
        for (MotorType type : MotorCatalogTemplates.typeOrder()) {
            MotorTypeTemplate tpl = MotorCatalogTemplates.of(type);
            for (MotorField f : tpl.fields()) {
                if (f.required()) {
                    keys.add(f.key());
                }
            }
        }
        return List.copyOf(keys);
    }
}
