/**
 * 不可变值对象（record / 内部 DTO）。
 *
 * <p>职责：承载 K1 的输入（工况、页签级设置、候选电机参数）与输出（规划曲线、理论值、
 * 判定结果、应用值、采样点），保证 Domain 层不依赖 Jackson / Spring 注解。
 *
 * <p>命名约束（02 C-C）：列表安全余量 {@code headroomRatio} 与应用值安全系数
 * {@code safetyPercent} 必须是不同字段名，禁止统一叫 margin。
 */
package com.motorsizer.domain.model;
