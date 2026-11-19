package com.motorsizer.app.importing;

import java.util.List;

/**
 * 转换统计（06 v2 §4）：上传后立即回传，前端据此展示「识别到什么」并给出下载入口。
 *
 * @param pageCount      PDF 总页数
 * @param charCount      抽到的有效字符数
 * @param hasTextLayer   是否含文字层
 * @param parsedPages    识别出参数表的页数
 * @param tableCount     参数表数量
 * @param modelCount     型号数
 * @param parameterRows  参数行数（不含分节行）
 * @param dataRows       Excel 长表数据行数
 * @param footnoteCount  脚注条数
 * @param skippedPages   未识别出参数表的页
 * @param excelName      建议下载文件名
 * @param message        人类可读结论（空结果时给出原因与建议）
 */
public record ImportStats(
        int pageCount,
        int charCount,
        boolean hasTextLayer,
        int parsedPages,
        int tableCount,
        int modelCount,
        int parameterRows,
        int dataRows,
        int footnoteCount,
        List<Integer> skippedPages,
        String excelName,
        String message) {
}
