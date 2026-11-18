package com.motorsizer.app.importing;

/** 导入任务状态机（06 §3）。 */
public enum JobStatus {
    UPLOADED,   // 文件已登记、页面清单已生成，尚未解析
    PARSED,     // 至少一页解析完成
    COMMITTED,  // 勾选草稿全部提交完毕（含部分失败：逐条结果见响应）
    CANCELLED   // 已删除（磁盘目录一并清理）
}
