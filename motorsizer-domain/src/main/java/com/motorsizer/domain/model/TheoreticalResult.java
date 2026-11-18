package com.motorsizer.domain.model;

/**
 * 理论力/力矩结果（仅含负载，不依赖所选电机）。
 *
 * <p>字段与原型 {@code calc.js:theoretical} 一致；{@code peak} **保留有符号**
 * （下坡工况为负，2026-11-18 已确认）。
 */
public record TheoreticalResult(
        double peak,
        double cruise,
        double decel,
        double accelTerm,
        double gravity,
        double cont,
        String contMethod,
        double cycleTime,
        double dwell,
        double resist) {
}
