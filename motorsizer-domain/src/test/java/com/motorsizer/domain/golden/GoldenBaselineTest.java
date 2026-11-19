package com.motorsizer.domain.golden;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * 黄金快照基线完整性测试（设计 02 R-2「快照冻结」）。
 *
 * <p>本测试不实现任何计算，只锁定三件事：
 * <ol>
 *   <li>快照未被手工篡改，且记录的原型哈希未漂移；</li>
 *   <li>已对齐参考软件的关键锚点仍然成立（114.480 / 83.804 等）；</li>
 *   <li>采样点数、S 曲线口径、格式化规则等已确认决策与快照一致。</li>
 * </ol>
 *
 * <p>K1 迁移完成后，Formula 测试将复用同一批 JSON 做逐项数值比对。
 */
class GoldenBaselineTest {

    /** prototype/js/calc.js 的 SHA-256；calc.js 变化后必须重新生成快照并更新此值。 */
    private static final String EXPECTED_CALC_SHA256 =
            "6fac2ab7f7b64934f4ff993170d55672e0bb821e0b1d7db758dada8037c6e2e6";

    private static final String FROZEN_AT = "2026-11-18";
    /** Java-vs-快照比对的相对容差（K1 迁移时使用）。 */
    private static final double TOLERANCE = 1e-9;
    /** 参考软件锚点是展示级 3 位小数，锚点断言使用 1e-6，避免把舍入误差误判为漂移。 */
    private static final double ANCHOR_TOLERANCE = 1e-6;
    private static final int SAMPLE_POINTS = 241;

    private static final List<String> SNAPSHOT_FILES = List.of(
            "fixtures.json",
            "motion.json",
            "theoretical.json",
            "screening.json",
            "applied.json",
            "sampling.json",
            "formatting.json");

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void snapshotsRecordFrozenBaseline() {
        for (String file : SNAPSHOT_FILES) {
            JsonNode meta = load(file).path("meta");
            assertThat(meta.path("baselineSha256").asText())
                    .as("%s 记录的 calc.js 哈希", file)
                    .isEqualTo(EXPECTED_CALC_SHA256);
            assertThat(meta.path("tolerance").asDouble()).as("%s 容差", file).isEqualTo(TOLERANCE);
            assertThat(meta.path("frozenAt").asText()).as("%s 冻结日期", file).isEqualTo(FROZEN_AT);
        }
    }

    @Test
    void theoreticalAnchorMatchesReferenceSoftware() {
        JsonNode result = findCase(load("theoretical.json").path("cases"),
                "linear-8kg-1m-1s-tilt90").path("result");

        assertThat(result.path("peak").asDouble()).isCloseTo(114.480, within(ANCHOR_TOLERANCE));
        assertThat(result.path("cont").asDouble()).isCloseTo(83.804, within(ANCHOR_TOLERANCE));
        assertThat(result.path("gravity").asDouble()).isCloseTo(78.480, within(ANCHOR_TOLERANCE));
        assertThat(result.path("cycleTime").asDouble()).isEqualTo(1.0);
    }

    @Test
    void theoreticalPeakKeepsSignOnDownhill() {
        JsonNode result = findCase(load("theoretical.json").path("cases"),
                "linear-8kg-1m-1s-tilt270").path("result");

        assertThat(result.path("peak").asDouble()).isCloseTo(-42.480, within(ANCHOR_TOLERANCE));
    }

    @Test
    void pointToPointClosedFormAnchors() {
        JsonNode trapezoid = findCase(load("motion.json").path("pointToPoint"),
                "p2p-trapezoid-030-05").path("result");
        assertThat(trapezoid.path("vPeak").asDouble()).isCloseTo(0.9, within(TOLERANCE));
        assertThat(trapezoid.path("aPeakIdeal").asDouble()).isCloseTo(5.4, within(TOLERANCE));
        assertThat(trapezoid.path("x").asDouble()).isCloseTo(0.5 / 3, within(TOLERANCE));
        assertThat(trapezoid.path("tCruise").asDouble()).isCloseTo(0.5 / 3, within(TOLERANCE));

        JsonNode triangle = findCase(load("motion.json").path("pointToPoint"),
                "p2p-triangle-030-05").path("result");
        assertThat(triangle.path("vPeak").asDouble()).isCloseTo(1.2, within(TOLERANCE));
        assertThat(triangle.path("aPeakIdeal").asDouble()).isCloseTo(4.8, within(TOLERANCE));
        assertThat(triangle.path("x").asDouble()).isCloseTo(0.25, within(TOLERANCE));
        assertThat(triangle.path("tCruise").asDouble()).isCloseTo(0, within(TOLERANCE));
    }

    @Test
    void smoothingLiftsAccelerationWithoutChangingPeakVelocity() {
        JsonNode curve = findCase(load("motion.json").path("smoothing"),
                "smooth-tau16-p2p-030-05").path("result").path("curve");

        assertThat(curve.path("smoothApplied").asBoolean()).isTrue();
        assertThat(curve.path("tau").asDouble()).isCloseTo(0.016, within(TOLERANCE));
        assertThat(curve.path("vPeak").asDouble()).isCloseTo(0.9, within(TOLERANCE));
        assertThat(curve.path("aPeakIdeal").asDouble()).isCloseTo(5.4, within(TOLERANCE));
        assertThat(curve.path("aPeak").asDouble()).isGreaterThan(5.4);
    }

    @Test
    void samplingUses241PointsIncludingBothEndpoints() {
        JsonNode sampling = load("sampling.json");
        for (JsonNode testCase : sampling.path("cases")) {
            String id = testCase.path("id").asText();
            double expectedTime = testCase.path("plan").path("input").path("time").asDouble();

            assertThat(testCase.path("pointCount").asInt()).as("%s 点数", id).isEqualTo(SAMPLE_POINTS);
            for (String series : List.of("t", "v", "a", "f")) {
                assertThat(testCase.path(series)).as("%s.%s 长度", id, series).hasSize(SAMPLE_POINTS);
            }
            assertThat(testCase.path("t").get(0).asDouble()).as("%s 起点", id).isEqualTo(0.0);
            assertThat(testCase.path("t").get(SAMPLE_POINTS - 1).asDouble())
                    .as("%s 终点", id)
                    .isCloseTo(expectedTime, within(TOLERANCE));
        }
    }

    @Test
    void missingRequiredSpecYieldsUnknownVerdict() {
        JsonNode result = findCase(load("screening.json").path("cases"),
                "linear-ilm20050-missing-thermal").path("result");

        assertThat(result.path("verdict").asText()).isEqualTo("unknown");

        List<String> missing = new ArrayList<>();
        result.path("missing").forEach(node -> missing.add(node.asText()));
        assertThat(missing).containsExactly("热耗散常数 ({cool})");
    }

    @Test
    void formattingRulesMatchPrototype() {
        JsonNode formatting = load("formatting.json");
        JsonNode fmt = formatting.path("fmt");

        assertThat(fmt.get(0).path("result").asText()).isEqualTo("1.235");
        assertThat(fmt.get(3).path("result").asText()).isEqualTo("1.23e-4");
        assertThat(fmt.get(4).path("result").asText()).isEqualTo("9.00e-4");

        JsonNode round = formatting.path("round");
        assertThat(round.get(1).path("result").asDouble()).isEqualTo(1.0);
        assertThat(round.get(2).path("result").asDouble()).isEqualTo(-1.0);

        assertThat(formatting.path("constants").path("ALPHA_CU").asDouble())
                .isCloseTo(0.00393, within(TOLERANCE));
        assertThat(formatting.path("constants").path("PRESETS")).hasSize(4);
    }

    private static JsonNode findCase(JsonNode cases, String id) {
        for (JsonNode testCase : cases) {
            if (id.equals(testCase.path("id").asText())) {
                return testCase;
            }
        }
        throw new AssertionError("快照中未找到用例: " + id);
    }

    private static JsonNode load(String file) {
        try (InputStream input = GoldenBaselineTest.class.getResourceAsStream("/golden/" + file)) {
            assertThat(input).as("快照资源存在: %s", file).isNotNull();
            return MAPPER.readTree(input);
        } catch (IOException ex) {
            throw new UncheckedIOException("读取快照失败: " + file, ex);
        }
    }
}
