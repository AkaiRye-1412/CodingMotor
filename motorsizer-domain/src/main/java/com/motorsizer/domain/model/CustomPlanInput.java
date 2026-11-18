package com.motorsizer.domain.model;

/**
 * 自定义模式输入。时间/速度/加速度均可为空，缺失项由系统反算。
 *
 * @param distance       运动距离（直线 m / 旋转 °），必须为正
 * @param time           运动时间（s），可选
 * @param maxVelocity    最大速度（直线 m/s / 旋转 °/s），可选
 * @param maxAcceleration 最大加速度（直线 m/s² / 旋转 °/s²），可选
 */
public record CustomPlanInput(
        double distance,
        Double time,
        Double maxVelocity,
        Double maxAcceleration) {
}
