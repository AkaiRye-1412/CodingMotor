package com.motorsizer.domain.model;

/**
 * 计算类错误码（04 §3 B1）：出现在响应的 {@code stage.code}，HTTP 仍为 200。
 */
public enum CalcErrorCode {

    PLAN_INVALID("CALC_PLAN_INVALID"),
    LIMIT_CONFLICT("CALC_LIMIT_CONFLICT"),
    SMOOTH_INFEASIBLE("CALC_SMOOTH_INFEASIBLE"),
    NO_AXIS_MATCH("CALC_NO_AXIS_MATCH");

    private final String code;

    CalcErrorCode(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }
}
