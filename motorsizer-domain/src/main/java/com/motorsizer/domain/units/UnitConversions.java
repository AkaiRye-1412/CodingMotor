package com.motorsizer.domain.units;

import com.motorsizer.domain.model.AxisKind;

/**
 * K3：单位与量纲桥接。
 *
 * <p>内部计算口径：直线用 m/s、m/s²、N；旋转用 °/s、°/s²、Nm。
 * 展示口径：旋转速度为 rpm（{@code 1 rpm = 6 °/s}），加速度为 rpm/s（{@code 1 rpm/s = 6 °/s²}）。
 */
public final class UnitConversions {

    /** 角度转弧度（物理公式使用）。 */
    public static final double DEG_TO_RAD = Math.PI / 180.0;

    private UnitConversions() {
    }

    /** 物理公式中的加速度换算：旋转 °/s² → rad/s²。 */
    public static double accelerationFactor(AxisKind kind) {
        return kind == AxisKind.ROTARY ? DEG_TO_RAD : 1.0;
    }

    /** 内部速度 → 展示速度：旋转 °/s → rpm。 */
    public static double speedToDisplay(AxisKind kind, double value) {
        return kind == AxisKind.ROTARY ? value / 6.0 : value;
    }

    /** 内部加速度 → 展示加速度：旋转 °/s² → rpm/s。 */
    public static double accelerationToDisplay(AxisKind kind, double value) {
        return kind == AxisKind.ROTARY ? value / 6.0 : value;
    }

    /** 展示速度 → 内部速度：旋转 rpm → °/s（请求体使用展示单位，进入 K1 前转换）。 */
    public static double speedToInternal(AxisKind kind, double value) {
        return kind == AxisKind.ROTARY ? value * 6.0 : value;
    }

    /** 展示加速度 → 内部加速度：旋转 rpm/s → °/s²。 */
    public static double accelerationToInternal(AxisKind kind, double value) {
        return kind == AxisKind.ROTARY ? value * 6.0 : value;
    }
}
