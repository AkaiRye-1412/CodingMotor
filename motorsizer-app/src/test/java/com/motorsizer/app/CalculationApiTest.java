package com.motorsizer.app;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.motorsizer.app.entity.MotorCoilSpecEntity;
import com.motorsizer.app.entity.MotorEntity;
import com.motorsizer.app.entity.MotorLinearSpecEntity;
import com.motorsizer.app.entity.MotorTorqueSpecEntity;
import com.motorsizer.app.mapper.MotorCoilSpecMapper;
import com.motorsizer.app.mapper.MotorLinearSpecMapper;
import com.motorsizer.app.mapper.MotorMapper;
import com.motorsizer.app.mapper.MotorTorqueSpecMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * B1 `POST /api/calculations` 契约测试。
 *
 * <p>每个用例前清空电机库，避免用例间候选池互相污染。
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:motorsizer_calc;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password="
})
@AutoConfigureMockMvc
class CalculationApiTest {

    private static final LocalDateTime NOW = LocalDateTime.now(ZoneOffset.UTC);

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private MotorMapper motorMapper;

    @Autowired
    private MotorLinearSpecMapper linearSpecMapper;

    @Autowired
    private MotorTorqueSpecMapper torqueSpecMapper;

    @Autowired
    private MotorCoilSpecMapper coilSpecMapper;

    @BeforeEach
    void resetMotorLibrary() {
        linearSpecMapper.delete(new QueryWrapper<>());
        torqueSpecMapper.delete(new QueryWrapper<>());
        coilSpecMapper.delete(new QueryWrapper<>());
        motorMapper.delete(new QueryWrapper<>());
    }

    // ---------------------------------------------------------------- 成功路径

    @Test
    void successReturnsFullContract() throws Exception {
        seedLinear("CALC-L1", 12, 48);

        JsonNode data = call(linearRequest(), 200).path("data");

        assertThat(data.path("stage").path("ok").asBoolean()).isTrue();
        assertThat(data.path("stage").path("code").asText()).isEqualTo("OK");
        assertThat(data.path("units").path("velocity").asText()).isEqualTo("m/s");
        assertThat(data.path("curve").path("mode").asText()).isEqualTo("pointToPoint");
        assertThat(data.path("curve").path("shape").asText()).isEqualTo("trapezoid");
        assertThat(data.path("curve").path("time").asDouble()).isCloseTo(1.0, within(1e-9));
        assertThat(data.path("theoretical").path("peakForce").asDouble()).isCloseTo(9.0, within(1e-6));
        assertThat(data.path("theoretical").path("contForce").asDouble()).isCloseTo(6.445, within(1e-3));

        assertThat(data.path("candidates")).hasSize(1);
        assertThat(data.path("candidates").get(0).path("modelName").asText()).isEqualTo("CALC-L1");
        assertThat(data.path("candidates").get(0).path("motorType").asText()).isEqualTo("linear");
        assertThat(data.path("candidates").get(0).path("headroomRatio").asDouble())
                .isCloseTo(12 / (6.445 * 1.3) - 1, within(1e-3));

        assertThat(isNullOrMissing(data.path("selectedMotor"))).isTrue();
        assertThat(data.path("sampling").path("pointCount").asInt()).isEqualTo(241);
        assertThat(data.path("sampling").path("speed")).hasSize(241);
        assertThat(data.path("sampling").path("force")).hasSize(241);
    }

    @Test
    void selectedMotorReturnsParametersAndAppliedValues() throws Exception {
        Long motorId = seedLinear("CALC-L1", 12, 48);

        Map<String, Object> body = linearRequest();
        body.put("selectedMotorId", motorId);
        JsonNode data = call(body, 200).path("data");

        JsonNode selected = data.path("selectedMotor");
        assertThat(selected.path("motorId").asLong()).isEqualTo(motorId);
        assertThat(selected.path("inRecommendation").asBoolean()).isTrue();
        assertThat(isNullOrMissing(selected.path("outOfRangeReason"))).isTrue();

        JsonNode parameters = selected.path("parameters");
        assertThat(parameters).hasSize(13);
        JsonNode km = findByKey(parameters, "km");
        assertThat(km.path("derived").asBoolean()).isTrue();
        assertThat(km.path("value").asDouble()).isGreaterThan(0);
        assertThat(findByKey(parameters, "prated").path("unit").asText()).isEqualTo("W");

        JsonNode applied = selected.path("applied");
        assertThat(applied.path("thermalVerdict").asText()).isEqualTo("pass");
        assertThat(applied.path("safetyPercent").asDouble()).isCloseTo(86.19, within(0.01));
        assertThat(applied.path("supply").path("pass").asBoolean()).isTrue();
        assertThat(applied.path("supply").path("text").asText()).contains("供电满足");
    }

    @Test
    void rotaryUnitsAndCurveAreDisplayedInRpm() throws Exception {
        seedTorque("CALC-R1", 4.5, 13.5);

        Map<String, Object> body = baseRequest("rotary", "pointToPoint");
        Map<String, Object> plan = new LinkedHashMap<>();
        plan.put("load", 0.05);
        plan.put("distance", 360.0);
        plan.put("time", 0.6);
        plan.put("dwell", 0.0);
        plan.put("tiltDeg", 90.0);
        plan.put("ambientTemp", 22.0);
        plan.put("supplyVoltage", 311.0);
        body.put("plan", plan);

        JsonNode data = call(body, 200).path("data");
        assertThat(data.path("units").path("velocity").asText()).isEqualTo("rpm");
        assertThat(data.path("units").path("force").asText()).isEqualTo("Nm");
        assertThat(data.path("curve").path("peakVelocity").asDouble()).isCloseTo(150.0, within(1e-6));
        assertThat(data.path("sampling").path("speed").get(240).path("v").asDouble())
                .isCloseTo(0.0, within(1e-9));
    }

    // ---------------------------------------------------------------- 业务失败（HTTP 200）

    @Test
    void customWithoutEnoughInputReturnsPlanInvalid() throws Exception {
        JsonNode data = call(customRequestWithoutLimits(), 200).path("data");

        assertThat(data.path("stage").path("ok").asBoolean()).isFalse();
        assertThat(data.path("stage").path("code").asText()).isEqualTo("CALC_PLAN_INVALID");
        assertThat(isNullOrMissing(data.path("curve"))).isTrue();
        assertThat(isNullOrMissing(data.path("candidates"))).isTrue();
        assertThat(isNullOrMissing(data.path("sampling"))).isTrue();
    }

    @Test
    void accelerationLimitConflictReturnsLimitConflict() throws Exception {
        Map<String, Object> body = baseRequest("linear", "custom");
        Map<String, Object> plan = new LinkedHashMap<>();
        plan.put("load", 2.0);
        plan.put("distance", 1.0);
        plan.put("time", 1.0);
        plan.put("maxAcceleration", 3.9);
        body.put("plan", plan);

        JsonNode data = call(body, 200).path("data");
        assertThat(data.path("stage").path("code").asText()).isEqualTo("CALC_LIMIT_CONFLICT");
    }

    @Test
    void infeasibleSmoothingReturnsBoundaryDetails() throws Exception {
        Map<String, Object> body = baseRequest("linear", "pointToPoint");
        Map<String, Object> plan = new LinkedHashMap<>();
        plan.put("load", 2.0);
        plan.put("distance", 0.1);
        plan.put("time", 0.2);
        body.put("plan", plan);
        body.put("smooth", Map.of("enabled", true, "tauMs", 56.0));

        JsonNode data = call(body, 200).path("data");
        assertThat(data.path("stage").path("code").asText()).isEqualTo("CALC_SMOOTH_INFEASIBLE");
        assertThat(data.path("stage").path("details").path("maxTau").asDouble())
                .isCloseTo(33.3, within(1e-9));
        assertThat(data.path("stage").path("details").path("closestPreset").asInt()).isEqualTo(16);
    }

    // ---------------------------------------------------------------- 校验失败（HTTP 400）

    @Test
    void pointToPointWithoutTimeIsRejected() throws Exception {
        Map<String, Object> body = linearRequest();
        @SuppressWarnings("unchecked")
        Map<String, Object> plan = (Map<String, Object>) body.get("plan");
        plan.remove("time");

        JsonNode root = call(body, 400);
        assertThat(root.path("code").asText()).isEqualTo("COMMON_VALIDATION_FAILED");
        assertThat(errorFields(root)).contains("plan.time");
    }

    @Test
    void invalidFilterValueIsRejected() throws Exception {
        Map<String, Object> body = linearRequest();
        body.put("filters", Map.of("structures", List.of("不存在的结构"), "coolings", List.of()));

        JsonNode root = call(body, 400);
        assertThat(errorFields(root)).contains("filters.structures[0]");
    }

    @Test
    void selectedMotorOfOtherAxisIsRejected() throws Exception {
        Long torqueMotorId = seedTorque("CALC-R1", 4.2, 12.6);

        Map<String, Object> body = linearRequest();
        body.put("selectedMotorId", torqueMotorId);

        JsonNode root = call(body, 400);
        assertThat(errorFields(root)).contains("selectedMotorId");
    }

    // ---------------------------------------------------------------- 候选与选中边界

    @Test
    void emptyMotorLibraryReturnsEmptyCandidates() throws Exception {
        JsonNode data = call(linearRequest(), 200).path("data");

        assertThat(data.path("stage").path("ok").asBoolean()).isTrue();
        assertThat(data.path("candidates")).isEmpty();
        assertThat(data.path("sampling").path("pointCount").asInt()).isEqualTo(241);
    }

    @Test
    void candidatesSortByHeadroomAndMarkOverSized() throws Exception {
        seedLinear("CALC-L1", 12, 48);
        seedLinear("CALC-L2", 22, 88);

        Map<String, Object> body = linearRequest();
        body.put("margin", Map.of("lowerPercent", 30.0, "upperPercent", 100.0, "upperEnabled", true));

        JsonNode candidates = call(body, 200).path("data").path("candidates");
        assertThat(candidates).hasSize(2);
        assertThat(candidates.get(0).path("modelName").asText()).isEqualTo("CALC-L1");
        assertThat(candidates.get(1).path("modelName").asText()).isEqualTo("CALC-L2");
        assertThat(candidates.get(1).path("overSized").asBoolean()).isTrue();
    }

    @Test
    void filteredOutSelectedMotorKeepsAppliedValues() throws Exception {
        Long motorId = seedLinear("CALC-L1", 12, 48);

        Map<String, Object> body = linearRequest();
        body.put("selectedMotorId", motorId);
        body.put("filters", Map.of("structures", List.of("有铁芯"), "coolings", List.of()));

        JsonNode selected = call(body, 200).path("data").path("selectedMotor");
        assertThat(selected.path("motorId").asLong()).isEqualTo(motorId);
        assertThat(selected.path("inRecommendation").asBoolean()).isFalse();
        assertThat(selected.path("outOfRangeReason").asText()).contains("筛选");
        assertThat(selected.path("applied").path("safetyPercent").asDouble()).isGreaterThan(0);
    }

    @Test
    void notAdmittedSelectedMotorReportsReason() throws Exception {
        Long motorId = seedLinear("CALC-L1", 1, 5);

        Map<String, Object> body = linearRequest();
        body.put("selectedMotorId", motorId);

        JsonNode selected = call(body, 200).path("data").path("selectedMotor");
        assertThat(selected.path("inRecommendation").asBoolean()).isFalse();
        assertThat(selected.path("outOfRangeReason").asText()).contains("未通过准入");
    }

    // ---------------------------------------------------------------- 请求构造与夹具

    private Map<String, Object> linearRequest() {
        return baseRequest("linear", "pointToPoint");
    }

    private Map<String, Object> baseRequest(String axis, String mode) {
        Map<String, Object> plan = new LinkedHashMap<>();
        plan.put("load", 2.0);
        plan.put("distance", 1.0);
        plan.put("time", 1.0);
        plan.put("dwell", 0.3);
        plan.put("tiltDeg", 0.0);
        plan.put("ambientTemp", 22.5);
        plan.put("supplyVoltage", 311.0);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("axisKind", axis);
        body.put("mode", mode);
        body.put("shape", "trapezoid");
        body.put("smooth", Map.of("enabled", false));
        body.put("plan", plan);
        body.put("margin", Map.of("lowerPercent", 30.0));
        body.put("filters", Map.of("structures", List.of(), "coolings", List.of()));
        return body;
    }

    private Map<String, Object> customRequestWithoutLimits() {
        Map<String, Object> body = baseRequest("linear", "custom");
        Map<String, Object> plan = new LinkedHashMap<>();
        plan.put("load", 2.0);
        plan.put("distance", 1.0);
        body.put("plan", plan);
        return body;
    }

    private Long seedLinear(String modelName, double forceCont, double forcePeak) {
        MotorEntity motor = commonMotor(modelName, "linear", "无铁芯", "普通");
        motorMapper.insert(motor);

        MotorLinearSpecEntity spec = new MotorLinearSpecEntity();
        spec.setMotorId(motor.getId());
        spec.setForceCont(BigDecimal(forceCont));
        spec.setForcePeak(BigDecimal(forcePeak));
        spec.setKf(BigDecimal(5.6));
        spec.setKe(BigDecimal(3.9));
        spec.setR25(BigDecimal(8.8));
        spec.setICont(BigDecimal(2.14));
        spec.setIPeak(BigDecimal(8.57));
        spec.setTMax(BigDecimal(110));
        spec.setKth(BigDecimal(1.1));
        spec.setUBus(BigDecimal(400));
        spec.setMaxSpeedPeak(BigDecimal(5.0));
        spec.setMaxSpeedCont(BigDecimal(6.0));
        spec.setCreatedAt(NOW);
        spec.setUpdatedAt(NOW);
        linearSpecMapper.insert(spec);
        return motor.getId();
    }

    private Long seedTorque(String modelName, double torqueCont, double torquePeak) {
        MotorEntity motor = commonMotor(modelName, "torque", "有铁芯", "普通");
        motorMapper.insert(motor);

        MotorTorqueSpecEntity spec = new MotorTorqueSpecEntity();
        spec.setMotorId(motor.getId());
        spec.setTorqueCont(BigDecimal(torqueCont));
        spec.setTorquePeak(BigDecimal(torquePeak));
        spec.setKt(BigDecimal(1.40));
        spec.setKe(BigDecimal(0.119));
        spec.setR25(BigDecimal(4.90));
        spec.setICont(BigDecimal(3.0));
        spec.setIPeak(BigDecimal(9.0));
        spec.setTMax(BigDecimal(100));
        spec.setKth(BigDecimal(1.1));
        spec.setUBus(BigDecimal(600));
        spec.setMaxSpeedPeak(BigDecimal(1000));
        spec.setMaxSpeedCont(BigDecimal(2000));
        spec.setCreatedAt(NOW);
        spec.setUpdatedAt(NOW);
        torqueSpecMapper.insert(spec);
        return motor.getId();
    }

    private MotorEntity commonMotor(String modelName, String type, String structure, String cooling) {
        MotorEntity motor = new MotorEntity();
        motor.setModelName(modelName);
        motor.setConfigName("默认");
        motor.setMotorType(type);
        motor.setStructure(structure);
        motor.setCooling(cooling);
        motor.setEntryMethod("手工录入");
        motor.setEnteredOn(LocalDate.of(2026, 11, 18));
        motor.setCreatedAt(NOW);
        motor.setUpdatedAt(NOW);
        return motor;
    }

    private static java.math.BigDecimal BigDecimal(double value) {
        return java.math.BigDecimal.valueOf(value);
    }

    private JsonNode call(Map<String, Object> body, int expectedStatus) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/calculations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().is(expectedStatus))
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private static List<String> errorFields(JsonNode root) {
        List<String> fields = new ArrayList<>();
        root.path("data").path("errors").forEach(error -> fields.add(error.path("field").asText()));
        return fields;
    }

    private static boolean isNullOrMissing(JsonNode node) {
        return node.isNull() || node.isMissingNode();
    }

    private static JsonNode findByKey(JsonNode rows, String key) {
        for (JsonNode row : rows) {
            if (key.equals(row.path("key").asText())) {
                return row;
            }
        }
        throw new AssertionError("参数行不存在: " + key);
    }
}
