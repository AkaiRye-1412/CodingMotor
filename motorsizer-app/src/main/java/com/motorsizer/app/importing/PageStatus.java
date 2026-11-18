package com.motorsizer.app.importing;

/** 页面级解析状态（06 §4）。 */
public enum PageStatus {
    PENDING,        // 未解析
    RECOGNIZED,     // 解析成功（可能生成 0 条草稿）
    EMPTY,          // 解析完成但未识别到任何字段
    OCR_REQUIRED,   // 无文本层（扫描 PDF / 图片），P0 阶段
    FAILED          // 解析异常
}
