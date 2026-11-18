package com.motorsizer.domain.golden;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.motorsizer.domain.model.CurveShape;
import com.motorsizer.domain.model.CustomPlanInput;
import com.motorsizer.domain.model.MotionCurve;
import com.motorsizer.domain.model.MotionResult;
import com.motorsizer.domain.model.PointToPointInput;
import com.motorsizer.domain.motion.MotionPlanner;
import com.motorsizer.domain.sampling.SamplingCalculator;
import com.motorsizer.domain.sampling.SamplingResult;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/** 曲线采样对黄金快照逐点比对（sampling.json）。 */
class SamplingGoldenTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void samplingCasesMatchGolden() throws IOException {
        JsonNode sampling = load();

        for (JsonNode testCase : sampling.path("cases")) {
            String id = testCase.path("id").asText();
            MotionCurve curve = curve(testCase.path("plan"));

            JsonNode forceModel = testCase.path("forceModel");
            SamplingResult result = SamplingCalculator.sample(
                    curve,
                    (acceleration, velocity) -> forceModel.path("mass").asDouble() * acceleration
                            + forceModel.path("resist").asDouble()
                            + forceModel.path("gravity").asDouble());

            int pointCount = testCase.path("pointCount").asInt();
            assertThat(result.pointCount()).as("%s.pointCount", id).isEqualTo(pointCount);

            assertSeries(id, "t", testCase.path("t"), result, Series.TIME);
            assertSeries(id, "v", testCase.path("v"), result, Series.VELOCITY);
            assertSeries(id, "a", testCase.path("a"), result, Series.ACCELERATION);
            assertSeries(id, "f", testCase.path("f"), result, Series.FORCE);
        }
    }

    private static void assertSeries(
            String id, String name, JsonNode expected, SamplingResult actual, Series series) {

        assertThat(expected).as("%s.%s 长度", id, name).hasSize(actual.pointCount());
        for (int i = 0; i < actual.pointCount(); i++) {
            double expectedValue = expected.get(i).asDouble();
            double actualValue = switch (series) {
                case TIME -> actual.points().get(i).time();
                case VELOCITY -> actual.points().get(i).velocity();
                case ACCELERATION -> actual.points().get(i).acceleration();
                case FORCE -> actual.points().get(i).force();
            };

            assertThat(actualValue).as("%s.%s[%d]", id, name, i)
                    .isCloseTo(expectedValue, within(1e-9 * Math.max(1, Math.abs(expectedValue))));
        }
    }

    private static MotionCurve curve(JsonNode planSpec) {
        MotionResult result = plan(planSpec);
        assertThat(result).as("规划应成功: %s", planSpec).isInstanceOf(MotionResult.Success.class);
        MotionCurve curve = ((MotionResult.Success) result).curve();

        if (planSpec.hasNonNull("tauMs")) {
            MotionResult smoothed = MotionPlanner.applySmoothing(curve, planSpec.path("tauMs").asDouble());
            assertThat(smoothed).as("平滑应成功: %s", planSpec).isInstanceOf(MotionResult.Success.class);
            return ((MotionResult.Success) smoothed).curve();
        }
        return curve;
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

    private static Double nullableDouble(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asDouble();
    }

    private static JsonNode load() throws IOException {
        try (InputStream input = SamplingGoldenTest.class.getResourceAsStream("/golden/sampling.json")) {
            assertThat(input).as("sampling.json 存在").isNotNull();
            return MAPPER.readTree(input);
        }
    }

    private enum Series {
        TIME, VELOCITY, ACCELERATION, FORCE
    }
}
