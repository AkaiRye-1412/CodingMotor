/**
 * K1：曲线采样（sampling）。
 *
 * <p>职责：速度-时间、力/力矩-时间序列。固定 240 点；S 曲线开启时斜坡段局部加密（T-7）。
 * 输出为 {@code {t, v}} / {@code {t, f}} 采样点，屏幕与报告共享同一份数据。
 *
 * <p>边界：纯 Java；不做绘制（绘制在 frontend/charts 与服务端报告）。
 */
package com.motorsizer.domain.sampling;
