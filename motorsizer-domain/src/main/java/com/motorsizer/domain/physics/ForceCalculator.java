package com.motorsizer.domain.physics;

import com.motorsizer.domain.model.AxisKind;
import com.motorsizer.domain.model.MotionCurve;
import com.motorsizer.domain.model.TheoreticalResult;
import com.motorsizer.domain.units.UnitConversions;

/**
 * K1 力学：理论力/力矩、重力分量、循环 RMS。
 *
 * <p>逐行等价于原型 {@code calc.js:gravityTerm/theoretical/rmsOfCycle}。
 */
public final class ForceCalculator {

    /** 重力加速度（m/s²），与原型一致。 */
    public static final double GRAVITY = 9.81;

    private ForceCalculator() {
    }

    /** 重力分量：仅直线参与；θ=90° 向上阻碍，270° 向下助动。 */
    public static double gravityTerm(AxisKind kind, double load, double tiltDeg) {
        if (kind != AxisKind.LINEAR) {
            return 0;
        }
        return load * GRAVITY * Math.sin(tiltDeg * UnitConversions.DEG_TO_RAD);
    }

    /**
     * 参与计算的总负载（2026-11-20 CAL-26）。
     *
     * <p>直线：总移动质量 = 用户负载 + 所选电机线圈质量（kg）；
     * 旋转：总惯量 = 用户负载惯量 + 所选电机转动惯量（kg·m²）。
     * 未选电机（或该电机未录入该项）时电机项为 0，与第 45 轮「不叠加电机自身质量」完全一致。
     */
    public static double totalLoad(double load, double motorMass) {
        return load + motorMass;
    }

    /**
     * 理论力/力矩。
     *
     * <p>{@code load} 为**总负载**（已由 {@link #totalLoad(double, double)} 叠加电机自身质量/惯量）。
     * 直线：F = m·a + F_res + m·g·sinθ；旋转：T = J·α + T_res（无重力项）。
     */
    public static TheoreticalResult theoretical(
            AxisKind kind,
            double load,
            MotionCurve curve,
            double resist,
            double dwell,
            double tiltDeg) {

        double accelTerm = load * curve.aPeak() * UnitConversions.accelerationFactor(kind);
        double gravity = gravityTerm(kind, load, tiltDeg);

        double peak = accelTerm + resist + gravity;
        double cruise = Math.abs(resist + gravity);
        double decel = Math.abs(accelTerm - resist - gravity);

        double cont = rmsOfCycle(
                peak, curve.tAccel(),
                cruise, curve.tCruise(),
                decel, curve.tDecel(),
                dwell);

        return new TheoreticalResult(
                peak,
                cruise,
                decel,
                accelTerm,
                gravity,
                cont,
                "rms",
                curve.time() + dwell,
                dwell,
                resist);
    }

    /** 循环 RMS：√(Σ(F²·t) / Σt)，停顿段出力为 0。 */
    public static double rmsOfCycle(
            double fAccel, double tAccel,
            double fCruise, double tCruise,
            double fDecel, double tDecel,
            double dwell) {

        double total = tAccel + tCruise + tDecel + dwell;
        if (total <= 0) {
            return 0;
        }
        double sum = fAccel * fAccel * tAccel
                + fCruise * fCruise * tCruise
                + fDecel * fDecel * tDecel;
        return Math.sqrt(Math.max(sum, 0) / total);
    }
}
