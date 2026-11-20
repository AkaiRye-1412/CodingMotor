package com.motorsizer.app.service;

import com.motorsizer.domain.model.AxisKind;
import com.motorsizer.domain.model.CurveShape;
import com.motorsizer.domain.model.MotionMode;
import com.motorsizer.domain.physics.ForceCalculator;

import java.util.List;

/** 校验并补默认值后的计算输入（仅供 application 层内部使用）。 */
record CalculationInput(
        AxisKind axis,
        MotionMode mode,
        CurveShape shape,
        boolean smoothEnabled,
        double tauMs,
        /** 用户填的负载本身：直线总移动质量 kg / 旋转负载惯量 kg·m²（不含电机自身）。 */
        double load,
        /** 选入电机的自身质量/惯量（CAL-26），未选电机时为 0。 */
        double motorMass,
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

    /** 参与计算的总负载 = 用户负载 + 电机自身质量/惯量；公式仍在 domain（AR-5）。 */
    double totalLoad() {
        return ForceCalculator.totalLoad(load, motorMass);
    }
}
