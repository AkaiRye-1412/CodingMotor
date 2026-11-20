package com.motorsizer.domain.model;

/**
 * 实际应用值的环境输入。
 *
 * @param load          参与计算的总负载：直线总移动质量 kg / 旋转总惯量 kg·m²，
 *                      已由调用方叠加选中电机的自身质量或转动惯量（CAL-26）
 * @param ambientTemp   环境温度（°C）；为空按 22 处理
 * @param supplyVoltage 供电母线电压（Vdc）；为空时不做供电判定
 * @param tiltDeg       倾斜角（°），仅直线参与重力计算
 */
public record AppliedEnvironment(
        double load,
        double resist,
        double dwell,
        Double ambientTemp,
        Double supplyVoltage,
        double tiltDeg) {
}
