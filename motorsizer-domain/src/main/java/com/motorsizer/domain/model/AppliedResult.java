package com.motorsizer.domain.model;

import java.util.List;

/**
 * 选定电机后的实际应用值（需求 5.2–5.4 / 第 45 轮口径）。
 *
 * <p>字段与原型 {@code calc.js:appliedValue} 一致；可空字段表示「缺参数/无法判定」，
 * 不得用 0 代替。
 */
public record AppliedResult(
        double totalInertia,
        double peakApplied,
        double cruiseApplied,
        double decelApplied,
        double gravity,
        double contApplied,
        Double iPeakRms,
        Double iContRms,
        Double iPeakMax,
        Double rHot,
        Double iAllow,
        Double tCoil,
        Boolean thermalPass,
        Double uReq,
        Double uAtAccel,
        Double uAtCruise,
        Double rVolt,
        List<String> uMissing,
        /** 热校核缺哪些电机参数（算温度 / 判超温各自需要什么，逐项列出）。 */
        List<String> thermalMissing,
        Double safety,
        Double loadCont,
        Double loadPeak,
        SupplyVerdict supply,
        Boolean overUbus,
        Verdict verdict) {

    public AppliedResult {
        uMissing = List.copyOf(uMissing);
        thermalMissing = thermalMissing == null ? List.of() : List.copyOf(thermalMissing);
    }
}
