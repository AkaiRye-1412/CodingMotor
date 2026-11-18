package com.motorsizer.domain.model;

/** 曲线形状：梯形 / 三角形（点到点可选；自定义由系统自动判定）。 */
public enum CurveShape {

    TRAPEZOID("trapezoid"),
    TRIANGLE("triangle");

    private final String code;

    CurveShape(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }

    public static CurveShape fromCode(String code) {
        for (CurveShape shape : values()) {
            if (shape.code.equals(code)) {
                return shape;
            }
        }
        throw new IllegalArgumentException("未知曲线形状: " + code);
    }
}
