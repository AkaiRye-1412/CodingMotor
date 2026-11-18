package com.motorsizer.domain.model;

/**
 * 运动规划结果曲线（不可变）。
 *
 * <p>字段与原型 {@code calc.js:buildCurve} 一一对应，便于黄金快照逐项比对：
 * <ul>
 *   <li>{@code dist} 直线为 m，旋转为 °；</li>
 *   <li>{@code time} 不含停顿时间；</li>
 *   <li>{@code x} 为单侧加速时间；梯形 {@code tCruise > 0}，三角形 {@code tCruise = 0}；</li>
 *   <li>{@code aPeakIdeal} 为未平滑的理论加速度，{@code aPeak} 为平滑后的实际上抬值；</li>
 *   <li>{@code jerk}/{@code taRamp} 仅在应用 S 曲线后非空。</li>
 * </ul>
 */
public record MotionCurve(
        MotionMode mode,
        double dist,
        double time,
        double x,
        double vPeak,
        double aPeakIdeal,
        double aPeak,
        CurveShape shape,
        boolean autoShape,
        AutoFlags auto,
        String note,
        double tau,
        boolean smoothApplied,
        boolean timeDerived,
        double tAccel,
        double tCruise,
        double tDecel,
        Double jerk,
        Double taRamp) {
}
