package com.motorsizer.domain.units;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.motorsizer.domain.model.AxisKind;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/** 显示格式化、取整与旋转单位换算的黄金快照比对（formatting.json）。 */
class ValueFormatterTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final double EPS = 1e-12;

    @Test
    void formatMatchesPrototype() throws IOException {
        JsonNode formatting = load();

        int index = 0;
        for (JsonNode testCase : formatting.path("fmt")) {
            JsonNode n = testCase.path("n");
            String actual = ValueFormatter.format(
                    testCase.path("value").asDouble(),
                    n.isNull() ? null : n.asInt());

            assertThat(actual)
                    .as("fmt 用例 #%d (%s)", index, testCase)
                    .isEqualTo(testCase.path("result").asText());
            index++;
        }
    }

    @Test
    void roundMatchesPrototype() throws IOException {
        JsonNode formatting = load();

        int index = 0;
        for (JsonNode testCase : formatting.path("round")) {
            JsonNode value = testCase.path("value");
            Double actual = ValueFormatter.round(
                    value.isNull() ? null : value.asDouble(),
                    testCase.path("n").asInt());

            if (testCase.path("result").isNull()) {
                assertThat(actual).as("round 用例 #%d", index).isNull();
            } else {
                assertThat(actual)
                        .as("round 用例 #%d", index)
                        .isCloseTo(testCase.path("result").asDouble(), within(EPS));
            }
            index++;
        }
    }

    @Test
    void unitConversionsMatchPrototype() throws IOException {
        JsonNode units = load().path("units");

        assertThat(UnitConversions.speedToDisplay(AxisKind.LINEAR, 1.5))
                .isCloseTo(units.path("linear").path("speedDisplayOf").asDouble(), within(EPS));
        assertThat(UnitConversions.accelerationFactor(AxisKind.LINEAR))
                .isCloseTo(units.path("linear").path("accelFactor").asDouble(), within(EPS));

        assertThat(UnitConversions.speedToDisplay(AxisKind.ROTARY, 6))
                .isCloseTo(units.path("rotary").path("speedDisplayOf").asDouble(), within(EPS));
        assertThat(UnitConversions.speedToDisplay(AxisKind.ROTARY, 540))
                .isCloseTo(units.path("rotary").path("speedDisplayOfCurve").asDouble(), within(EPS));
        assertThat(UnitConversions.accelerationFactor(AxisKind.ROTARY))
                .isCloseTo(units.path("rotary").path("accelFactor").asDouble(), within(EPS));
        assertThat(UnitConversions.accelerationToDisplay(AxisKind.ROTARY, 10))
                .isCloseTo(units.path("rotary").path("accelDisplayOf").asDouble(), within(EPS));
    }

    private static JsonNode load() throws IOException {
        try (InputStream input = ValueFormatterTest.class.getResourceAsStream("/golden/formatting.json")) {
            assertThat(input).as("formatting.json 存在").isNotNull();
            return MAPPER.readTree(input);
        }
    }
}
