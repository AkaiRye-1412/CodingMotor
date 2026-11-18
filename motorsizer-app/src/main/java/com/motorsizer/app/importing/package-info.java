/**
 * 样册识别模块（06 设计，接口 I1–I13；P0 已实现，P1 OCR 待接入）。
 *
 * <p>职责：导入任务状态机、PDF 文本层页面解析（含多列对照表，Q1）、字段映射（别名入 K2，Q2）、
 * 草稿编辑与批量提交（逐条独立事务，Q4）。临时文件位于 {@code %APPDATA%\MotorSizer\imports}，
 * 任务与草稿存 {@code job.json}，不落数据库；启动期 30 天惰性清理（Q3）。
 *
 * <p>边界：只通过 S1 的保存用例写电机库（复用 C3 校验与事务，{@link com.motorsizer.app.service.MotorService#create}）；
 * 不触碰 K1/K3/S3/S4；字段 schema 来自 K2。图片/扫描页（无文本层）返回 OCR_REQUIRED，待 P1 Tess4J 接入。
 */
package com.motorsizer.app.importing;
