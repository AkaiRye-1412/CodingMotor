package com.motorsizer.domain.golden;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.motorsizer.domain.catalog.MotorType;
import com.motorsizer.domain.model.AppliedEnvironment;
import com.motorsizer.domain.model.AppliedResult;
import com.motorsizer.domain.model.AxisKind;
import com.motorsizer.domain.model.CurveShape;
import com.motorsizer.domain.model.CustomPlanInput;
import com.motorsizer.domain.model.MotionCurve;
import com.motorsizer.domain.model.MotionResult;
import com.motorsizer.domain.model.MotorEvaluation;
import com.motorsizer.domain.model.MotorParameters;
import com.motorsizer.domain.model.PointToPointInput;
import com.motorsizer.domain.model.SupplyVerdict;
import com.motorsizer.domain.model.TheoreticalResult;
import com.motorsizer.domain.motion.MotionPlanner;
import com.motorsizer.domain.physics.AppliedCalculator;
import com.motorsizer.domain.physics.ForceCalculator;
import com.motorsizer.domain.screening.MotorEvaluator;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * 理论力、实际应用值与准入判定的黄金快照比对（theoretical/applied/screening）。
 */
class PhysicsScreeningGoldenTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void theoreticalCasesMatchGolden() throws IOException {
        JsonNode theoretical = load("theoretical.json");

        for (JsonNode testCase : theoretical.path("cases")) {
            String id = testCase.path("id").asText();
            JsonNode expected = testCase.path("result");

            TheoreticalResult actual = theoretical(testCase);

            assertClose(id + ".peak", expected.path("peak"), actual.peak());
            assertClose(id + ".cruise", expected.path("cruise"), actual.cruise());
            assertClose(id + ".decel", expected.path("decel"), actual.decel());
            assertClose(id + ".accelTerm", expected.path("accelTerm"), actual.accelTerm());
            assertClose(id + ".gravity", expected.path("gravity"), actual.gravity());
            assertClose(id + ".cont", expected.path("cont"), actual.cont());
            assertClose(id + ".cycleTime", expected.path("cycleTime"), actual.cycleTime());
            assertClose(id + ".dwell", expected.path("dwell"), actual.dwell());
            assertClose(id + ".resist", expected.path("resist"), actual.resist());
            assertThat(actual.contMethod()).as("%s.contMethod", id).isEqualTo("rms");
        }
    }

    @Test
    void appliedCasesMatchGolden() throws IOException {
        JsonNode applied = load("applied.json");
        JsonNode theoretical = load("theoretical.json");

        for (JsonNode testCase : applied.path("cases")) {
            String id = testCase.path("id").asText();
            JsonNode expected = testCase.path("result");

            JsonNode theoreticalCase = findCase(theoretical.path("cases"),
                    testCase.path("theoreticalRef").asText());
            TheoreticalResult theoreticalResult = theoretical(theoreticalCase);
            MotionCurve curve = curve(theoreticalCase.path("plan"));

            AxisKind kind = AxisKind.fromCode(testCase.path("kind").asText());
            MotorParameters motor = motor(testCase.path("motorId").asText());
            AppliedResult actual = AppliedCalculator.applied(
                    kind, motor, theoreticalResult, curve, environment(testCase.path("env")));

            assertClose(id + ".totalInertia", expected.path("totalInertia"), actual.totalInertia());
            assertClose(id + ".peakApplied", expected.path("peakApplied"), actual.peakApplied());
            assertClose(id + ".cruiseApplied", expected.path("cruiseApplied"), actual.cruiseApplied());
            assertClose(id + ".decelApplied", expected.path("decelApplied"), actual.decelApplied());
            assertClose(id + ".gravity", expected.path("gravity"), actual.gravity());
            assertClose(id + ".contApplied", expected.path("contApplied"), actual.contApplied());

            assertNullableClose(id + ".iPeakRms", expected.path("iPeakRms"), actual.iPeakRms());
            assertNullableClose(id + ".iContRms", expected.path("iContRms"), actual.iContRms());
            assertNullableClose(id + ".iPeakMax", expected.path("iPeakMax"), actual.iPeakMax());
            assertNullableClose(id + ".rHot", expected.path("rHot"), actual.rHot());
            assertNullableClose(id + ".iAllow", expected.path("iAllow"), actual.iAllow());
            assertNullableClose(id + ".tCoil", expected.path("tCoil"), actual.tCoil());
            assertNullableBoolean(id + ".thermalPass", expected.path("thermalPass"), actual.thermalPass());

            assertNullableClose(id + ".uReq", expected.path("uReq"), actual.uReq());
            assertNullableClose(id + ".uAtAccel", expected.path("uAtAccel"), actual.uAtAccel());
            assertNullableClose(id + ".uAtCruise", expected.path("uAtCruise"), actual.uAtCruise());
            assertNullableClose(id + ".rVolt", expected.path("rVolt"), actual.rVolt());
            assertThat(actual.uMissing()).as("%s.uMissing", id)
                    .containsExactlyElementsOf(strings(expected.path("uMissing")));

            assertNullableClose(id + ".safety", expected.path("safety"), actual.safety());
            assertNullableClose(id + ".loadCont", expected.path("loadCont"), actual.loadCont());
            assertNullableClose(id + ".loadPeak", expected.path("loadPeak"), actual.loadPeak());
            assertNullableBoolean(id + ".overUbus", expected.path("overUbus"), actual.overUbus());
            assertThat(actual.verdict().code()).as("%s.verdict", id)
                    .isEqualTo(expected.path("verdict").asText());

            assertSupply(id + ".supply", expected.path("supply"), actual.supply());
        }
    }

    @Test
    void screeningCasesMatchGolden() throws IOException {
        JsonNode screening = load("screening.json");
        JsonNode theoretical = load("theoretical.json");

        for (JsonNode testCase : screening.path("cases")) {
            String id = testCase.path("id").asText();
            JsonNode expected = testCase.path("result");

            JsonNode theoreticalCase = findCase(theoretical.path("cases"),
                    testCase.path("theoreticalRef").asText());
            TheoreticalResult theoreticalResult = theoretical(theoreticalCase);
            MotionCurve curve = curve(theoreticalCase.path("plan"));
            MotorParameters motor = motor(testCase.path("motorId").asText());

            MotorEvaluation actual = MotorEvaluator.evaluate(
                    AxisKind.fromCode(testCase.path("kind").asText()),
                    motor,
                    theoreticalResult,
                    curve,
                    testCase.path("marginLow").asDouble());

            assertThat(actual.verdict().code()).as("%s.verdict", id)
                    .isEqualTo(expected.path("verdict").asText());
            assertThat(actual.pass()).as("%s.pass", id).isEqualTo(expected.path("pass").asBoolean());

            assertNullableBoolean(id + ".passCont", expected.path("passCont"), actual.passCont());
            assertNullableBoolean(id + ".passPeak", expected.path("passPeak"), actual.passPeak());
            assertNullableBoolean(id + ".passSpeedPeak", expected.path("passSpeedPeak"), actual.passSpeedPeak());
            assertNullableBoolean(id + ".passSpeedCont", expected.path("passSpeedCont"), actual.passSpeedCont());

            assertThat(actual.speedSkipped()).as("%s.speedSkipped", id)
                    .containsExactlyElementsOf(strings(expected.path("speedSkipped")));
            assertThat(actual.reasons()).as("%s.reasons", id)
                    .containsExactlyElementsOf(strings(expected.path("reasons")));

            assertNullableClose(id + ".headroom", expected.path("headroom"), actual.headroom());
            assertNullableClose(id + ".overSize", expected.path("overSize"), actual.overSize());
            assertNullableClose(id + ".contHave", expected.path("contHave"), actual.contHave());
            assertNullableClose(id + ".peakHave", expected.path("peakHave"), actual.peakHave());
            assertNullableClose(id + ".contNeed", expected.path("contNeed"), actual.contNeed());
            assertNullableClose(id + ".peakNeed", expected.path("peakNeed"), actual.peakNeed());

            List<String> missingLabels = new ArrayList<>();
            actual.missing().forEach(field -> missingLabels.add(field.label()));
            assertThat(missingLabels).as("%s.missing", id)
                    .containsExactlyElementsOf(strings(expected.path("missing")));
        }
    }

    /* ---------------- 快照构造 ---------------- */

    private static TheoreticalResult theoretical(JsonNode testCase) {
        MotionCurve curve = curve(testCase.path("plan"));
        JsonNode env = testCase.path("env");
        return ForceCalculator.theoretical(
                AxisKind.fromCode(testCase.path("kind").asText()),
                testCase.path("load").asDouble(),
                curve,
                testCase.path("resist").asDouble(),
                env.path("dwell").asDouble(),
                env.path("tilt").asDouble());
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

    private static AppliedEnvironment environment(JsonNode env) {
        return new AppliedEnvironment(
                env.path("load").asDouble(),
                env.path("resist").asDouble(),
                env.path("dwell").asDouble(),
                env.hasNonNull("tAmbient") ? env.path("tAmbient").asDouble() : null,
                env.hasNonNull("uSupply") ? env.path("uSupply").asDouble() : null,
                env.path("tilt").asDouble());
    }

    private static MotorParameters motor(String motorId) throws IOException {
        JsonNode motorNode = load("fixtures.json").path("motors").path(motorId);
        assertThat(motorNode.isObject()).as("夹具电机存在: %s", motorId).isTrue();

        MotorType type = MotorType.fromCode(motorNode.path("type").asText());
        Map<String, Double> values = new HashMap<>();
        motorNode.fields().forEachRemaining(entry -> {
            if (entry.getValue().isNumber()) {
                values.put(apiKey(entry.getKey()), entry.getValue().asDouble());
            }
        });
        return MotorParameters.of(type, values);
    }

    /** 原型夹具字段 → K2/API 驼峰字段。 */
    private static String apiKey(String key) {
        return switch (key) {
            case "Kf" -> "kf";
            case "Kt" -> "kt";
            case "Ke" -> "ke";
            case "Km" -> "km";
            case "R25" -> "r25";
            case "L" -> "l";
            case "Te" -> "te";
            case "Pn" -> "pn";
            case "Icont" -> "iCont";
            case "Ipeak" -> "iPeak";
            case "Tmax" -> "tMax";
            case "Kth" -> "kth";
            case "Ubus" -> "uBus";
            case "coilLen" -> "coilLength";
            case "mass" -> "totalMass";
            case "J" -> "inertia";
            default -> key;
        };
    }

    /* ---------------- 断言工具 ---------------- */

    private static void assertSupply(String label, JsonNode expected, SupplyVerdict actual) {
        if (expected.isNull() || expected.isMissingNode()) {
            assertThat(actual).as(label).isNull();
            return;
        }
        assertThat(actual).as(label).isNotNull();
        assertClose(label + ".have", expected.path("have"), actual.have());
        assertClose(label + ".need", expected.path("need"), actual.need());
        assertClose(label + ".remain", expected.path("remain"), actual.remain());
        assertClose(label + ".rate", expected.path("rate"), actual.rate());
        assertThat(actual.pass()).as(label + ".pass").isEqualTo(expected.path("pass").asBoolean());
        assertThat(actual.text()).as(label + ".text").isEqualTo(expected.path("text").asText());
    }

    private static void assertClose(String label, JsonNode expected, double actual) {
        double expectedValue = expected.asDouble();
        assertThat(actual).as(label)
                .isCloseTo(expectedValue, within(1e-9 * Math.max(1, Math.abs(expectedValue))));
    }

    private static void assertNullableClose(String label, JsonNode expected, Double actual) {
        if (expected.isNull() || expected.isMissingNode()) {
            assertThat(actual).as(label).isNull();
            return;
        }
        assertThat(actual).as(label).isNotNull();
        assertClose(label, expected, actual);
    }

    private static void assertNullableBoolean(String label, JsonNode expected, Boolean actual) {
        if (expected.isNull() || expected.isMissingNode()) {
            assertThat(actual).as(label).isNull();
            return;
        }
        assertThat(actual).as(label).isEqualTo(expected.asBoolean());
    }

    private static List<String> strings(JsonNode array) {
        if (array.isNull() || array.isMissingNode()) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        array.forEach(node -> values.add(node.asText()));
        return values;
    }

    private static Double nullableDouble(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asDouble();
    }

    private static JsonNode findCase(JsonNode cases, String id) {
        for (JsonNode testCase : cases) {
            if (id.equals(testCase.path("id").asText())) {
                return testCase;
            }
        }
        throw new AssertionError("快照中未找到用例: " + id);
    }

    private static JsonNode load(String file) throws IOException {
        try (InputStream input = PhysicsScreeningGoldenTest.class.getResourceAsStream("/golden/" + file)) {
            assertThat(input).as("快照资源存在: %s", file).isNotNull();
            return MAPPER.readTree(input);
        }
    }
}
