/**
 * S4：报告服务（D1）。
 *
 * <p>职责：逐方案复用 {@code service.CalculationService} 的同一用例，组装报告模型 →
 * Thymeleaf 渲染 HTML → OpenHTMLtoPDF 转 PDF（内嵌 Noto Sans SC）。
 * 模板位于 {@code resources/report-templates}，字体位于 {@code resources/fonts}。
 *
 * <p>边界：不重新实现任何计算；曲线图按 Domain 采样数据重绘，不与前端共享渲染代码。
 * Controller 仍放在 {@code controller} 包，本包只承载报告组装与渲染。
 */
package com.motorsizer.app.report;
