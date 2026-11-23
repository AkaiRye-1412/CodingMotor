package com.motorsizer.domain.motion;

import com.motorsizer.domain.model.AutoFlags;
import com.motorsizer.domain.model.CalcErrorCode;
import com.motorsizer.domain.model.CurveShape;
import com.motorsizer.domain.model.CustomPlanInput;
import com.motorsizer.domain.model.MotionCurve;
import com.motorsizer.domain.model.MotionMode;
import com.motorsizer.domain.model.MotionResult;
import com.motorsizer.domain.model.PointToPointInput;
import com.motorsizer.domain.units.ValueFormatter;

/**
 * K1 运动规划：点到点、自定义反解、S 曲线平滑。
 *
 * <p>除 CAL-27（自定义只给速度时按标准梯形 1:3 反解，原型直接报错）外，逐行等价于原型
 * {@code prototype/js/calc.js}（冻结基线）；其余任何行为差异都会破坏黄金快照对比（R-2）。
 * 浮点容差由测试层负责，本类不做额外取整。
 */
public final class MotionPlanner {

    private static final int[] PRESETS = {0, 4, 16, 56};

    private MotionPlanner() {
    }

    /** 点到点：梯形 x = T/3；三角形 x = T/2（无匀速段）。 */
    public static MotionResult planPointToPoint(PointToPointInput input) {
        if (!Double.isFinite(input.distance()) || input.distance() <= 0) {
            return MotionResult.Failure.of(CalcErrorCode.PLAN_INVALID, "距离必须为正数");
        }
        if (!Double.isFinite(input.time()) || input.time() <= 0) {
            return MotionResult.Failure.of(CalcErrorCode.PLAN_INVALID, "运动时间必须为正数");
        }

        double distance = input.distance();
        double time = input.time();
        boolean triangle = input.shape() == CurveShape.TRIANGLE;
        double x = triangle ? time / 2 : time / 3;
        double vPeak = distance / (time - x);
        double aPeak = distance / (x * (time - x));

        MotionCurve curve = buildCurve(
                MotionMode.POINT_TO_POINT,
                distance,
                time,
                x,
                vPeak,
                aPeak,
                triangle ? CurveShape.TRIANGLE : CurveShape.TRAPEZOID,
                false,
                AutoFlags.NONE,
                triangle
                        ? "三角形：匀速时间为 0，加速/减速各占 T/2（1:3 比例不适用）"
                        : "梯形：加速 / 匀速 / 减速各占运动时间 1/3",
                false);

        return new MotionResult.Success(curve);
    }

    /**
     * 自定义：形状由系统判定，缺失参数反解。
     *
     * <p>已知时间的口径优先级：加速度上限 &gt; 速度上限 &gt; 默认 1:3；未知时间时：
     * 「速度 + 加速度」反算时间 →「只给速度」按标准梯形 1:3 反解（CAL-27）→ 三项全空才报错。
     *
     * <p>质量/惯量不参与本方法：它只在 {@code F = m·a} 处影响推力/转矩，与运动学反解无关。
     */
    public static MotionResult planCustom(CustomPlanInput input) {
        double distance = input.distance();
        if (!Double.isFinite(distance) || distance <= 0) {
            return MotionResult.Failure.of(CalcErrorCode.PLAN_INVALID, "距离必须为正数");
        }

        Double time = positiveOrNull(input.time());
        Double velocityLimit = positiveOrNull(input.maxVelocity());
        Double accelerationLimit = positiveOrNull(input.maxAcceleration());
        AutoFlags auto = new AutoFlags(
                time == null,
                velocityLimit == null,
                accelerationLimit == null);

        // ---------- 情形一：已知运动时间 ----------
        if (time != null) {
            double triangleAcceleration = 4 * distance / (time * time);
            double triangleVelocity = 2 * distance / time;
            Double x = null;
            String how = "";

            if (accelerationLimit != null && accelerationLimit < triangleAcceleration - 1e-12) {
                return MotionResult.Failure.of(CalcErrorCode.LIMIT_CONFLICT,
                        "最大加速度 " + ValueFormatter.format(accelerationLimit, 3)
                                + " 不足以在 " + ValueFormatter.format(time, 4)
                                + " s 内走完 " + ValueFormatter.format(distance, 4)
                                + "\n所需加速度下限为 " + ValueFormatter.format(triangleAcceleration, 3)
                                + "（三角形是加速度需求最低的形状）。\n"
                                + "请提高加速度上限，或延长运动时间");
            }

            if (accelerationLimit != null) {
                double discriminant = accelerationLimit * accelerationLimit * time * time
                        - 4 * accelerationLimit * distance;
                x = (accelerationLimit * time - Math.sqrt(Math.max(discriminant, 0)))
                        / (2 * accelerationLimit);
                how = "由最大加速度 " + ValueFormatter.format(accelerationLimit, 3) + " 反解加速时间";
            }

            if (velocityLimit != null) {
                double xFromVelocity = time - distance / velocityLimit;
                if (xFromVelocity <= 0) {
                    return MotionResult.Failure.of(CalcErrorCode.LIMIT_CONFLICT,
                            "最大速度 " + ValueFormatter.format(velocityLimit, 3)
                                    + " 过低：仅匀速段就需 " + ValueFormatter.format(distance / velocityLimit, 4)
                                    + " s，\n已超过运动时间 " + ValueFormatter.format(time, 4)
                                    + " s。请提高最大速度或延长运动时间");
                }
                if (x == null) {
                    x = Math.min(xFromVelocity, time / 2);
                    how = Math.abs(xFromVelocity - time / 2) < 1e-12
                            ? "最大速度 " + ValueFormatter.format(velocityLimit, 3)
                            + " 恰为三角形峰值 " + ValueFormatter.format(triangleVelocity, 3)
                            : "由最大速度 " + ValueFormatter.format(velocityLimit, 3) + " 反解加速时间";
                } else if (x > xFromVelocity + 1e-12) {
                    return MotionResult.Failure.of(CalcErrorCode.LIMIT_CONFLICT,
                            "速度与加速度上限互相矛盾：\n"
                                    + "按加速度 " + ValueFormatter.format(accelerationLimit, 3)
                                    + " 加速，峰值速度需 " + ValueFormatter.format(distance / (time - x), 3)
                                    + "，超过最大速度 " + ValueFormatter.format(velocityLimit, 3) + "。\n"
                                    + "请放宽其中之一，或延长运动时间");
                }
            }

            if (x == null) {
                x = time / 3;
                how = "未给速度与加速度上限，按标准梯形默认（加/匀/减各占 1/3）";
            }

            return finalizePlan(x, time, distance, how, auto, velocityLimit);
        }

        // ---------- 情形二：未给运动时间 → 反算时间 ----------
        if (accelerationLimit != null) {
            if (velocityLimit != null) {
                double xTry = velocityLimit / accelerationLimit;
                double tTry = distance / velocityLimit + xTry;
                if (xTry <= tTry / 2 - 1e-12) {
                    return finalizePlan(xTry, tTry, distance,
                            "由「最大速度 + 最大加速度」反算运动时间 " + ValueFormatter.format(tTry, 4) + " s",
                            auto, velocityLimit);
                }
            }

            double x = Math.sqrt(distance / accelerationLimit);
            double triangleTime = 2 * x;
            double triangleVelocity = accelerationLimit * x;
            if (velocityLimit != null && triangleVelocity > velocityLimit + 1e-12) {
                return MotionResult.Failure.of(CalcErrorCode.LIMIT_CONFLICT,
                        "按最大加速度 " + ValueFormatter.format(accelerationLimit, 3)
                                + " 形成的三角形峰值速度 " + ValueFormatter.format(triangleVelocity, 3)
                                + " 超过最大速度 " + ValueFormatter.format(velocityLimit, 3)
                                + "\n请降低加速度、提高速度上限，或直接给出运动时间");
            }
            return finalizePlan(x, triangleTime, distance,
                    "由最大加速度 " + ValueFormatter.format(accelerationLimit, 3)
                            + " 反算运动时间 " + ValueFormatter.format(triangleTime, 4) + " s（结果恰为三角形）",
                    auto, velocityLimit);
        }

        if (velocityLimit != null) {
            // CAL-27：只给速度也能求解——把速度上限当作峰值速度，按标准梯形（加/匀/减各占 1/3）
            // 反解时间与加速度：d = v·(t − t/3) → t = 1.5d/v，a = v/(t/3) = 2v²/d。
            double derivedTime = 1.5 * distance / velocityLimit;
            return finalizePlan(derivedTime / 3, derivedTime, distance,
                    "未给时间与加速度：由最大速度 " + ValueFormatter.format(velocityLimit, 3)
                            + " 按标准梯形（加速/匀速/减速各占 1/3）反算运动时间 "
                            + ValueFormatter.format(derivedTime, 4) + " s",
                    auto, velocityLimit);
        }

        return MotionResult.Failure.of(CalcErrorCode.PLAN_INVALID,
                "运动时间、最大速度与最大加速度全部留空，无法求解\n"
                        + "请至少补充其一：「运动时间」（直接反解），或「最大速度」"
                        + "（按标准梯形 1:3 反解时间与加速度）");
    }

    /**
     * 应用 S 曲线平滑：{@code vPeak} 不变、{@code aPeak} 上抬；可行条件 {@code x >= 2τ}。
     */
    public static MotionResult applySmoothing(MotionCurve curve, double tauMs) {
        double tau = Double.isFinite(tauMs) ? tauMs / 1000.0 : 0.0;

        if (tau <= 0) {
            return new MotionResult.Success(new MotionCurve(
                    curve.mode(), curve.dist(), curve.time(), curve.x(),
                    curve.vPeak(), curve.aPeakIdeal(), curve.aPeakIdeal(),
                    curve.shape(), curve.autoShape(), curve.auto(), curve.note(),
                    tau, false, curve.timeDerived(),
                    curve.tAccel(), curve.tCruise(), curve.tDecel(),
                    curve.jerk(), curve.taRamp()));
        }

        if (curve.x() < 2 * tau) {
            double maxTau = curve.x() / 2 * 1000;
            String message = "加速段时间 " + ValueFormatter.format(curve.x() * 1000, 1)
                    + " ms 不足以容纳 " + ValueFormatter.format(tauMs, 1)
                    + " ms 平滑（需 ≥ " + ValueFormatter.format(2 * tauMs, 1) + " ms）\n"
                    + "当前工况允许的最大平滑时间为 " + ValueFormatter.format(maxTau, 1) + " ms";
            return new MotionResult.Failure(
                    CalcErrorCode.SMOOTH_INFEASIBLE,
                    message,
                    ValueFormatter.round(maxTau, 1),
                    closestPreset(maxTau));
        }

        double aPeak = curve.vPeak() / (curve.x() - tau);
        return new MotionResult.Success(new MotionCurve(
                curve.mode(), curve.dist(), curve.time(), curve.x(),
                curve.vPeak(), curve.aPeakIdeal(), aPeak,
                curve.shape(), curve.autoShape(), curve.auto(), curve.note(),
                tau, true, curve.timeDerived(),
                curve.x(), curve.time() - 2 * curve.x(), curve.x(),
                aPeak / tau, tau));
    }

    private static MotionResult finalizePlan(
            double x, double time, double distance, String how, AutoFlags auto, Double velocityLimit) {

        boolean triangle = Math.abs(x - time / 2) < Math.max(1e-12, time * 1e-9);
        if (triangle) {
            x = time / 2;
        } else {
            x = Math.min(Math.max(x, 1e-12), time / 2);
        }

        double vPeak = distance / (time - x);
        double aPeak = distance / (x * (time - x));

        if (velocityLimit != null && vPeak > velocityLimit + 1e-9 * Math.max(1, velocityLimit)) {
            return MotionResult.Failure.of(CalcErrorCode.LIMIT_CONFLICT,
                    "解得的峰值速度 " + ValueFormatter.format(vPeak, 3)
                            + " 超过最大速度上限 " + ValueFormatter.format(velocityLimit, 3) + "，工况矛盾");
        }

        MotionCurve curve = buildCurve(
                MotionMode.CUSTOM,
                distance,
                time,
                x,
                vPeak,
                aPeak,
                triangle ? CurveShape.TRIANGLE : CurveShape.TRAPEZOID,
                true,
                auto,
                how,
                auto.time());

        return new MotionResult.Success(curve);
    }

    private static MotionCurve buildCurve(
            MotionMode mode,
            double distance,
            double time,
            double x,
            double vPeak,
            double aPeak,
            CurveShape shape,
            boolean autoShape,
            AutoFlags auto,
            String note,
            boolean timeDerived) {

        return new MotionCurve(
                mode,
                distance,
                time,
                x,
                vPeak,
                aPeak,
                aPeak,
                shape,
                autoShape,
                auto,
                note,
                0.0,
                false,
                timeDerived,
                x,
                time - 2 * x,
                x,
                null,
                null);
    }

    private static Double positiveOrNull(Double value) {
        if (value == null || !Double.isFinite(value) || value <= 0) {
            return null;
        }
        return value;
    }

    private static Integer closestPreset(double maxTau) {
        Integer closest = null;
        for (int preset : PRESETS) {
            if (preset <= maxTau && preset > 0) {
                closest = preset;
            }
        }
        return closest;
    }
}
