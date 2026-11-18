package com.motorsizer.domain.model;

/** 结论三态：通过 / 不通过 / 信息不全无法判定。 */
public enum Verdict {

    PASS("pass"),
    FAIL("fail"),
    UNKNOWN("unknown");

    private final String code;

    Verdict(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }
}
