package com.motorsizer.domain.sampling;

/**
 * 采样点的力/力矩取值函数：由调用方按当前工况给出。
 *
 * @param acceleration 该点加速度（内部单位）
 * @param velocity     该点速度（内部单位）
 */
@FunctionalInterface
public interface ForceFunction {

    double forceAt(double acceleration, double velocity);
}
