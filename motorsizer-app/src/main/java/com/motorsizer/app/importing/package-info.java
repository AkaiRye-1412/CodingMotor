/**
 * 样册转 Excel 模块（06 v2，2026-11-19 改版）。
 *
 * <p>职责：接收上传的一个样册 PDF，用坐标级版面解析（表头中心线定列、底色块定分节）
 * 识别参数表，输出 xlsx（参数汇总 / 每型号原版式 / 脚注说明 / 识别报告）。
 *
 * <p>边界：**不写电机库**——草稿、字段校验、勾选提交、入库等链路已按用户要求取消，
 * 电机库仍由手工录入维护；解析算法在 {@code importing.render}，与
 * {@code .workbuddy/tmp/pdf2excel.py} 保持一一对应。任务与产物存
 * {@code %APPDATA%\MotorSizer\imports\{jobId}\}，不落数据库，启动期 30 天惰性清理。
 */
package com.motorsizer.app.importing;
