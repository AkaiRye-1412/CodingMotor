package com.motorsizer.domain.model;

/**
 * 点到点输入。
 *
 * @param distance 运动距离（直线 m / 旋转 °），必须为正
 * @param time     运动时间（s，不含停顿），必须为正
 * @param shape    形状；{@code null} 视为梯形（与原型 {@code shape === 'triangle'} 判定一致）
 */
public record PointToPointInput(double distance, double time, CurveShape shape) {

    public PointToPointInput(double distance, double time) {
        this(distance, time, null);
    }
}
