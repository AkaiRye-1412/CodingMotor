package com.motorsizer.app.dto;

import java.util.List;

/** B1 请求体（04 §3 B1）。字段可空，由服务端统一校验。 */
public record CalculationRequest(
        String axisKind,
        String mode,
        String shape,
        Smooth smooth,
        Plan plan,
        Margin margin,
        Filters filters,
        Long selectedMotorId) {

    public record Smooth(Boolean enabled, Double tauMs) {
    }

    public record Plan(
            Double load,
            Double distance,
            Double time,
            Double dwell,
            Double maxVelocity,
            Double maxAcceleration,
            Double resistance,
            Double tiltDeg,
            Double ambientTemp,
            Double supplyVoltage) {
    }

    public record Margin(Double lowerPercent, Double upperPercent, Boolean upperEnabled) {
    }

    public record Filters(List<String> structures, List<String> coolings) {
    }
}
