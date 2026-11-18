package com.motorsizer.app.service;

import com.motorsizer.domain.model.AxisKind;
import com.motorsizer.domain.model.CurveShape;
import com.motorsizer.domain.model.MotionMode;

import java.util.List;

/** 校验并补默认值后的计算输入（仅供 application 层内部使用）。 */
record CalculationInput(
        AxisKind axis,
        MotionMode mode,
        CurveShape shape,
        boolean smoothEnabled,
        double tauMs,
        double load,
        double distance,
        Double time,
        double dwell,
        Double maxVelocity,
        Double maxAcceleration,
        double resistance,
        double tiltDeg,
        double ambientTemp,
        Double supplyVoltage,
        double marginLow,
        double marginUpper,
        boolean marginUpperEnabled,
        List<String> structures,
        List<String> coolings,
        Long selectedMotorId) {
}
