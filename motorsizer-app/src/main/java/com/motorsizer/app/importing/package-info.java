/**
 * I1：样册识别（v1 补充，接口 I1–I13）。
 *
 * <p>职责：导入任务状态机、PDF/图片页面定位、表格解析、字段映射与命名、草稿编辑与批量提交。
 * 临时文件位于 {@code %APPDATA%\MotorSizer\imports}，任务与草稿存 {@code job.json}，不落数据库。
 *
 * <p>边界：只通过 S1 的保存用例写电机库（复用 C3 校验与事务）；
 * 不触碰 K1/K3/S3/S4；字段 schema 来自 K2。
 */
package com.motorsizer.app.importing;
