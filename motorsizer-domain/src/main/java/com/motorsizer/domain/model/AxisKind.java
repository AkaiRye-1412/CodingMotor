package com.motorsizer.domain.model;

/** 计算页签：直线 / 旋转（对应 API 的 {@code axisKind}）。 */
public enum AxisKind {

    LINEAR("linear"),
    ROTARY("rotary");

    private final String code;

    AxisKind(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }

    public static AxisKind fromCode(String code) {
        for (AxisKind kind : values()) {
            if (kind.code.equals(code)) {
                return kind;
            }
        }
        throw new IllegalArgumentException("未知 axisKind: " + code);
    }
}
