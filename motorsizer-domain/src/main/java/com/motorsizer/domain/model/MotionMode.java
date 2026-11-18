package com.motorsizer.domain.model;

/** 运动模式：点到点 / 自定义（对应 API 的 {@code mode}）。 */
public enum MotionMode {

    POINT_TO_POINT("pointToPoint"),
    CUSTOM("custom");

    private final String code;

    MotionMode(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }
}
