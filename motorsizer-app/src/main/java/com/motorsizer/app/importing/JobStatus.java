package com.motorsizer.app.importing;

/**
 * 转换任务状态（2026-11-19 改版：样册识别只做「PDF → Excel」）。
 *
 * <p>不再有草稿/提交/入库阶段，状态只描述这一次转换的结果：
 * <ul>
 *   <li>{@code READY} —— 识别出至少一张参数表，Excel 已生成可下载；</li>
 *   <li>{@code EMPTY} —— PDF 无文字层（扫描件）或整本没识别到参数表，Excel 仍给出识别报告页；</li>
 *   <li>{@code FAILED} —— 解析/写盘异常（正常路径下接口会直接报错，保留供磁盘上的历史任务自洽）。</li>
 * </ul>
 */
public enum JobStatus {
    READY,
    EMPTY,
    FAILED
}
