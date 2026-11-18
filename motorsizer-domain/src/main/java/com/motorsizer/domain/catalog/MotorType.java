package com.motorsizer.domain.catalog;

import com.motorsizer.domain.model.AxisKind;

/**
 * 电机类型。取值与数据库 {@code motor.motor_type} 的 CHECK 约束一致。
 */
public enum MotorType {

    LINEAR("linear", "直线"),
    TORQUE("torque", "力矩"),
    COIL("coil", "音圈");

    private final String code;
    private final String label;

    MotorType(String code, String label) {
        this.code = code;
        this.label = label;
    }

    public String code() {
        return code;
    }

    public String label() {
        return label;
    }

    /** 该类型参与哪个页签的计算：音圈是直线运动。 */
    public AxisKind axis() {
        return this == TORQUE ? AxisKind.ROTARY : AxisKind.LINEAR;
    }

    /** 按接口/DTO 中的字符串取值解析；非法值返回 {@code null}，由调用方给出校验错误。 */
    public static MotorType fromCode(String code) {
        for (MotorType type : values()) {
            if (type.code.equals(code)) {
                return type;
            }
        }
        return null;
    }
}
