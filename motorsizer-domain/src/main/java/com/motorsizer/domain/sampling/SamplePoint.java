package com.motorsizer.domain.sampling;

/** 单个采样点：时间、速度、加速度、力/力矩。 */
public record SamplePoint(double time, double velocity, double acceleration, double force) {
}
