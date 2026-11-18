package com.motorsizer.domain.golden;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.motorsizer.domain.model.CalcErrorCode;
import com.motorsizer.domain.model.CurveShape;
import com.motorsizer.domain.model.CustomPlanInput;
import com.motorsizer.domain.model.MotionCurve;
import com.motorsizer.domain.model.MotionMode;
import com.motorsizer.domain.model.MotionResult;
import com.motorsizer.domain.model.PointToPointInput;
import com.motorsizer.domain.motion.MotionPlanner;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * 运动规划对黄金快照逐项比对（motion.json）。
 *
 * <p>成功用例比较全部曲线字段；失败用例比较错误码与完整文案。
 */
class MotionGoldenTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final double EPS = 1e-9;

    private static final Map<String, CalcErrorCode> CUSTOM_FAILURE_CODES = Map.of(
            "custom-invalid-dist", CalcErrorCode.PLAN_INVALID,
            "custom-only-velocity-error-1-v2", CalcErrorCode.PLAN_INVALID,
            "custom-accel-conflict-1-1-a39", CalcErrorCode.LIMIT_CONFLICT,
            "custom-velocity-conflict-1-1-v09", CalcErrorCode.LIMIT_CONFLICT,
            "custom-both-conflict-1-1-a5-v12", CalcErrorCode.LIMIT_CONFLICT);

    @Test
    void pointToPointCasesMatchGolden() throws IOException {
        JsonNode root = load();

        for (JsonNode testCase : root.path("pointToPoint")) {
            String id = testCase.path("id").asText();
            JsonNode input = testCase.path("input");
            JsonNode expected = testCase.path("result");

            MotionResult result = MotionPlanner.planPointToPoint(new PointToPointInput(
                    input.path("dist").asDouble(),
                    input.path("time").asDouble(),
                    input.hasNonNull("shape") ? CurveShape.fromCode(input.path("shape").asText()) : null));

            assertOutcome(id, expected, result, CalcErrorCode.PLAN_INVALID);
        }
    }

    @Test
    void customCasesMatchGolden() throws IOException {
        JsonNode root = load();

        for (JsonNode testCase : root.path("custom")) {
            String id = testCase.path("id").asText();
            JsonNode input = testCase.path("input");
            JsonNode expected = testCase.path("result");

            MotionResult result = MotionPlanner.planCustom(new CustomPlanInput(
                    input.path("dist").asDouble(),
                    nullableDouble(input, "time"),
                    nullableDouble(input, "vMax"),
                    nullableDouble(input, "aMax")));

            CalcErrorCode expectedFailureCode = CUSTOM_FAILURE_CODES.getOrDefault(
                    id, CalcErrorCode.PLAN_INVALID);
            assertOutcome(id, expected, result, expectedFailureCode);
        }
    }

    @Test
    void smoothingCasesMatchGolden() throws IOException {
        JsonNode root = load();

        for (JsonNode testCase : root.path("smoothing")) {
            String id = testCase.path("id").asText();
            JsonNode expected = testCase.path("result");

            MotionResult base = plan(testCase.path("basePlan"));
            assertThat(base).as("%s 的基础曲线可算", id).isInstanceOf(MotionResult.Success.class);
            MotionCurve baseCurve = ((MotionResult.Success) base).curve();

            MotionResult result = MotionPlanner.applySmoothing(
                    baseCurve, testCase.path("tauMs").asDouble());

            if (expected.path("ok").asBoolean()) {
                assertThat(result).as("%s 应成功", id).isInstanceOf(MotionResult.Success.class);
                assertCurve(expected.path("curve"), ((MotionResult.Success) result).curve(), id);
                continue;
            }

            assertThat(result).as("%s 应失败", id).isInstanceOf(MotionResult.Failure.class);
            MotionResult.Failure failure = (MotionResult.Failure) result;
            assertThat(failure.code()).as("%s 错误码", id).isEqualTo(CalcErrorCode.SMOOTH_INFEASIBLE);
            assertThat(failure.message()).as("%s 文案", id).isEqualTo(expected.path("message").asText());
            assertThat(failure.maxTau()).as("%s maxTau", id)
                    .isCloseTo(expected.path("maxTau").asDouble(), within(EPS));

            if (expected.path("closest").isNull()) {
                assertThat(failure.closestPreset()).as("%s closest", id).isNull();
            } else {
                assertThat(failure.closestPreset()).as("%s closest", id)
                        .isEqualTo(expected.path("closest").asInt());
            }
        }
    }

    private static void assertOutcome(
            String id, JsonNode expected, MotionResult result, CalcErrorCode expectedFailureCode) {

        if (expected.path("ok").asBoolean()) {
            assertThat(result).as("%s 应成功", id).isInstanceOf(MotionResult.Success.class);
            assertCurve(expected, ((MotionResult.Success) result).curve(), id);
            return;
        }

        assertThat(result).as("%s 应失败", id).isInstanceOf(MotionResult.Failure.class);
        MotionResult.Failure failure = (MotionResult.Failure) result;
        assertThat(failure.code()).as("%s 错误码", id).isEqualTo(expectedFailureCode);
        assertThat(failure.message()).as("%s 文案", id).isEqualTo(expected.path("message").asText());
        assertThat(failure.maxTau()).as("%s maxTau", id).isNull();
        assertThat(failure.closestPreset()).as("%s closest", id).isNull();
    }

    private static void assertCurve(JsonNode expected, MotionCurve actual, String id) {
        assertThat(actual.dist()).as("%s dist", id)
                .isCloseTo(expected.path("dist").asDouble(), within(EPS));
        assertThat(actual.time()).as("%s time", id)
                .isCloseTo(expected.path("time").asDouble(), within(EPS));
        assertThat(actual.x()).as("%s x", id)
                .isCloseTo(expected.path("x").asDouble(), within(EPS));
        assertThat(actual.vPeak()).as("%s vPeak", id)
                .isCloseTo(expected.path("vPeak").asDouble(), within(EPS));
        assertThat(actual.aPeakIdeal()).as("%s aPeakIdeal", id)
                .isCloseTo(expected.path("aPeakIdeal").asDouble(), within(EPS));
        assertThat(actual.aPeak()).as("%s aPeak", id)
                .isCloseTo(expected.path("aPeak").asDouble(), within(EPS));
        assertThat(actual.tAccel()).as("%s tAccel", id)
                .isCloseTo(expected.path("tAccel").asDouble(), within(EPS));
        assertThat(actual.tCruise()).as("%s tCruise", id)
                .isCloseTo(expected.path("tCruise").asDouble(), within(EPS));
        assertThat(actual.tDecel()).as("%s tDecel", id)
                .isCloseTo(expected.path("tDecel").asDouble(), within(EPS));
        assertThat(actual.tau()).as("%s tau", id)
                .isCloseTo(expected.path("tau").asDouble(), within(EPS));

        assertThat(actual.mode()).as("%s mode", id)
                .isEqualTo(modeFromLegacyCode(expected.path("mode").asText()));
        assertThat(actual.shape().code()).as("%s shape", id).isEqualTo(expected.path("shape").asText());
        assertThat(actual.autoShape()).as("%s autoShape", id)
                .isEqualTo(expected.path("autoShape").asBoolean());
        assertThat(actual.smoothApplied()).as("%s smoothApplied", id)
                .isEqualTo(expected.path("smoothApplied").asBoolean());
        assertThat(actual.timeDerived()).as("%s timeDerived", id)
                .isEqualTo(expected.path("timeDerived").asBoolean());

        JsonNode auto = expected.path("auto");
        assertThat(actual.auto().time()).as("%s auto.time", id).isEqualTo(auto.path("time").asBoolean());
        assertThat(actual.auto().vMax()).as("%s auto.vMax", id).isEqualTo(auto.path("vMax").asBoolean());
        assertThat(actual.auto().aMax()).as("%s auto.aMax", id).isEqualTo(auto.path("aMax").asBoolean());

        if (expected.hasNonNull("note")) {
            assertThat(actual.note()).as("%s note", id).isEqualTo(expected.path("note").asText());
        } else {
            assertThat(actual.note()).as("%s note", id).isNull();
        }

        if (expected.hasNonNull("jerk")) {
            assertThat(actual.jerk()).as("%s jerk", id)
                    .isCloseTo(expected.path("jerk").asDouble(), within(EPS));
        } else {
            assertThat(actual.jerk()).as("%s jerk", id).isNull();
        }

        if (expected.hasNonNull("taRamp")) {
            assertThat(actual.taRamp()).as("%s taRamp", id)
                    .isCloseTo(expected.path("taRamp").asDouble(), within(EPS));
        } else {
            assertThat(actual.taRamp()).as("%s taRamp", id).isNull();
        }
    }

    private static MotionResult plan(JsonNode planSpec) {
        JsonNode input = planSpec.path("input");
        if ("planPointToPoint".equals(planSpec.path("fn").asText())) {
            return MotionPlanner.planPointToPoint(new PointToPointInput(
                    input.path("dist").asDouble(),
                    input.path("time").asDouble(),
                    input.hasNonNull("shape") ? CurveShape.fromCode(input.path("shape").asText()) : null));
        }
        if ("planCustom".equals(planSpec.path("fn").asText())) {
            return MotionPlanner.planCustom(new CustomPlanInput(
                    input.path("dist").asDouble(),
                    nullableDouble(input, "time"),
                    nullableDouble(input, "vMax"),
                    nullableDouble(input, "aMax")));
        }
        throw new AssertionError("未知规划函数: " + planSpec.path("fn").asText());
    }

    private static MotionMode modeFromLegacyCode(String legacyCode) {
        return "p2p".equals(legacyCode) ? MotionMode.POINT_TO_POINT : MotionMode.CUSTOM;
    }

    private static Double nullableDouble(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asDouble();
    }

    private static JsonNode load() throws IOException {
        try (InputStream input = MotionGoldenTest.class.getResourceAsStream("/golden/motion.json")) {
            assertThat(input).as("motion.json 存在").isNotNull();
            return MAPPER.readTree(input);
        }
    }
}
