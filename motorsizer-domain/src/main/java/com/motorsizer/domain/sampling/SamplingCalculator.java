package com.motorsizer.domain.sampling;

import com.motorsizer.domain.model.MotionCurve;

import java.util.ArrayList;
import java.util.List;

/**
 * K1 曲线采样：逐行等价于原型 {@code calc.js:sampleCurve}。
 *
 * <p>固定 240 个等分区间（输出 241 点，含起止两端）。S 曲线开启时，
 * 速度/加速度按 jerk 斜坡公式计算，但采样密度仍为均匀分布。
 */
public final class SamplingCalculator {

    /** 等分区间数；输出点数为 {@code CURVE_SAMPLES + 1}。 */
    public static final int CURVE_SAMPLES = 240;

    private SamplingCalculator() {
    }

    public static SamplingResult sample(MotionCurve curve, ForceFunction forceFunction) {
        int intervals = CURVE_SAMPLES;
        double time = curve.time();
        double x = curve.x();
        double peakVelocity = curve.vPeak();
        double peakAcceleration = curve.aPeak();
        double tau = curve.tau();

        List<SamplePoint> points = new ArrayList<>(intervals + 1);

        for (int i = 0; i <= intervals; i++) {
            double t = (time * i) / intervals;
            double velocity = 0;
            double acceleration = 0;

            if (t <= x) {
                if (tau > 0 && t < tau) {
                    // jerk 上升
                    acceleration = (peakAcceleration / tau) * t;
                    velocity = 0.5 * (peakAcceleration / tau) * t * t;
                } else if (tau > 0 && t < x - tau) {
                    acceleration = peakAcceleration;
                    velocity = 0.5 * peakAcceleration * tau + peakAcceleration * (t - tau);
                } else if (tau > 0) {
                    double delta = t - (x - tau);
                    acceleration = peakAcceleration - (peakAcceleration / tau) * delta;
                    double velocityAtRampStart = 0.5 * peakAcceleration * tau
                            + peakAcceleration * (x - 2 * tau);
                    velocity = velocityAtRampStart + peakAcceleration * delta
                            - 0.5 * (peakAcceleration / tau) * delta * delta;
                } else {
                    acceleration = peakAcceleration;
                    velocity = peakAcceleration * t;
                }
                if (velocity > peakVelocity) {
                    velocity = peakVelocity;
                }
            } else if (t <= time - x) {
                velocity = peakVelocity;
                acceleration = 0;
            } else {
                double delta = t - (time - x);
                if (tau > 0 && delta < x - tau) {
                    if (delta < tau) {
                        acceleration = -(peakAcceleration / tau) * delta;
                        velocity = peakVelocity - 0.5 * (peakAcceleration / tau) * delta * delta;
                    } else {
                        acceleration = -peakAcceleration;
                        velocity = peakVelocity - 0.5 * peakAcceleration * tau
                                - peakAcceleration * (delta - tau);
                    }
                } else if (tau > 0) {
                    double d = delta - (x - tau);
                    acceleration = -peakAcceleration + (peakAcceleration / tau) * d;
                    double velocityAtRampStart = peakVelocity - 0.5 * peakAcceleration * tau
                            - peakAcceleration * (x - 2 * tau);
                    velocity = velocityAtRampStart - peakAcceleration * d
                            + 0.5 * (peakAcceleration / tau) * d * d;
                } else {
                    acceleration = -peakAcceleration;
                    velocity = peakVelocity - peakAcceleration * delta;
                }
                if (velocity < 0) {
                    velocity = 0;
                }
            }

            points.add(new SamplePoint(
                    t, velocity, acceleration, forceFunction.forceAt(acceleration, velocity)));
        }

        return new SamplingResult(points);
    }
}
