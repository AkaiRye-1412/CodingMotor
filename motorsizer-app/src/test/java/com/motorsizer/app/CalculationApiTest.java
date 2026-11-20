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

    /**
     * CAL-26：选入电机的线圈质量并入总移动质量，理论推力按「负载 + 电机质量」计算。
     *
     * <p>基准用例负载 2 kg、峰值推力 9 N（a_peak = 4.5 m/s²）；再叠加 3 kg 后总质量 5 kg，
     * 峰值推力应同比放大到 22.5 N —— 加速度曲线本身不随质量变化。
     */
    @Test
    void motorMassIsAddedToTotalMovingMass() throws Exception {
        JsonNode base = call(linearRequest(), 200).path("data");
        assertThat(base.path("theoretical").path("peakForce").asDouble()).isCloseTo(9.0, within(1e-6));

        Map<String, Object> withMass = linearRequest();
        planOf(withMass).put("motorMass", 3.0);
        JsonNode data = call(withMass, 200).path("data");

        assertThat(data.path("theoretical").path("peakForce").asDouble())
                .isCloseTo(base.path("theoretical").path("peakForce").asDouble() * 2.5, within(1e-6));
        // 采样曲线上的力也必须含电机质量，不能只有理论值变了
        assertThat(maxSampleForce(data))
                .isCloseTo(maxSampleForce(base) * 2.5, within(1e-3));
    }

    @Test
    void selectedMotorAppliesMotorMassToAppliedValues() throws Exception {
        Long motorId = seedLinear("CALC-L1", 12, 48);

        Map<String, Object> withoutMass = linearRequest();
        withoutMass.put("selectedMotorId", motorId);
        double basePeakCurrent = call(withoutMass, 200).path("data")
                .path("selectedMotor").path("applied").path("peakCurrent").asDouble();

        Map<String, Object> withMass = linearRequest();
        withMass.put("selectedMotorId", motorId);
        planOf(withMass).put("motorMass", 2.0);
        double massPeakCurrent = call(withMass, 200).path("data")
                .path("selectedMotor").path("applied").path("peakCurrent").asDouble();

        // 总质量翻倍 → 峰值电流同步翻倍（kf 不变）
        assertThat(massPeakCurrent).isCloseTo(basePeakCurrent * 2.0, within(1e-6));
    }

    @Test
    void negativeMotorMassIsRejected() throws Exception {
        Map<String, Object> body = linearRequest();
        planOf(body).put("motorMass", -1.0);

        JsonNode error = call(body, 400);

        assertThat(error.path("code").asText()).isEqualTo("COMMON_VALIDATION_FAILED");
        assertThat(errorFields(error)).contains("plan.motorMass");
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
        // 2026-11-20：直线模板有 11 个「识别」字段进参数表（9 项性能参数 + 线圈质量/长度），
        // 派生的「额定功率 / 最大功率」不再出现——样册上识别不到的东西不展示。
        // 2026-11-21：uBus（最高母线电压）进参数表、线圈长度移出 → 仍是 11 项。
        assertThat(parameters).hasSize(11);
        List<String> keys = new ArrayList<>();
        parameters.forEach(parameter -> keys.add(parameter.path("key").asText()));
        assertThat(keys).doesNotContain("prated", "pmax");
        assertThat(keys).contains("uBus").doesNotContain("coilLength");
        JsonNode km = findByKey(parameters, "km");
        assertThat(km.path("derived").asBoolean()).isTrue();
        assertThat(km.path("value").asDouble()).isGreaterThan(0);
        assertThat(findByKey(parameters, "moverMass").path("unit").asText()).isEqualTo("kg");

        JsonNode applied = selected.path("applied");
        assertThat(applied.path("thermalVerdict").asText()).isEqualTo("pass");
        assertThat(applied.path("safetyPercent").asDouble()).isCloseTo(86.19, within(0.01));
        assertThat(applied.path("supply").path("pass").asBoolean()).isTrue();
        assertThat(applied.path("supply").path("text").asText()).contains("供电满足");
    }

    /**
     * 样册入库的电机没有「最高线圈温度」（采集口径剔除了这一行）：按默认 100 °C 校核。
     *
     * <p>原实现把 {@code tCoil} 的守卫写成「rHot 有值」，而 rHot 依赖 tMax →
     * 缺 tMax 时温度显示「—」，提示语还写死成「缺 热耗散常数 Kth」，用户照着补永远补不对。
     * 现在缺 tMax 时温度照算，超温判定按 100 °C（用户裁定），热校核不再报「数据不足」。
     */
    @Test
    void missingMaxCoilTempFallsBackToDefault100() throws Exception {
        Long motorId = seedLinear("CALC-L-NOTMAX", 12, 48, null);

        Map<String, Object> body = linearRequest();
        body.put("selectedMotorId", motorId);
        JsonNode applied = call(body, 200).path("data").path("selectedMotor").path("applied");

        // 温度只用 r25 / kth / 实际电流迭代就行，不需要 tMax
        assertThat(applied.path("coilTemp").isNumber()).isTrue();
        assertThat(applied.path("coilTemp").asDouble()).isGreaterThan(22.0);
        // tMax 有缺省值 → 热校核缺项为空，能给出 pass/fail 而不是 unknown
        assertThat(applied.path("thermalMissing")).isEmpty();
        assertThat(applied.path("thermalVerdict").asText()).isEqualTo("pass");
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

    /**
     * 第 67 轮：旋转页「结构」筛选替换为「系列」，按型号名前缀过滤。
     * 勾选 ADR 时只列出 ADR 开头的力矩型号，ACD 与无前缀型号被排除。
     */
    @Test
    void rotaryFiltersByModelSeriesPrefix() throws Exception {
        seedTorque("ADR175-A102-S", 4.5, 13.5);
        seedTorque("ADR220-87-S", 5.0, 15.0);
        seedTorque("ACD85-P-15-Y", 4.5, 13.5);
        seedTorque("XYZ-99", 4.5, 13.5);

        Map<String, Object> body = rotaryRequest();
        body.put("filters", Map.of("structures", List.of("ADR"), "coolings", List.of()));

        JsonNode candidates = call(body, 200).path("data").path("candidates");
        assertThat(candidates).hasSize(2);
        assertThat(candidates.get(0).path("modelName").asText()).isEqualTo("ADR175-A102-S");
        assertThat(candidates.get(1).path("modelName").asText()).isEqualTo("ADR220-87-S");
    }

    /** 第 67 轮：「其他」采用排除法——型号名不以任一已知前缀开头即归入。 */
    @Test
    void rotaryOtherSeriesExcludesKnownPrefixes() throws Exception {
        seedTorque("ADR175-A102-S", 4.5, 13.5);
        seedTorque("XYZ-99", 4.5, 13.5);

        Map<String, Object> body = rotaryRequest();
        body.put("filters", Map.of("structures", List.of("其他"), "coolings", List.of()));

        JsonNode candidates = call(body, 200).path("data").path("candidates");
        assertThat(candidates).hasSize(1);
        assertThat(candidates.get(0).path("modelName").asText()).isEqualTo("XYZ-99");
    }

    /** 第 68 轮：直线页「结构」筛选同样替换为「系列」，按型号名前缀过滤。 */
    @Test
    void linearFiltersByModelSeriesPrefix() throws Exception {
        seedLinear("ACM100-A", 12, 48);
        seedLinear("AKM200-B4", 12, 48);
        seedLinear("CALC-L1", 12, 48);

        Map<String, Object> body = linearRequest();
        body.put("filters", Map.of("structures", List.of("ACM"), "coolings", List.of()));

        JsonNode candidates = call(body, 200).path("data").path("candidates");
        assertThat(candidates).hasSize(1);
        assertThat(candidates.get(0).path("modelName").asText()).isEqualTo("ACM100-A");
    }

    /**
     * 第 69 轮：直线页新增「结构」（冷却方式）筛选。
     *
     * <p>型号结尾 {@code -A} 算气冷、{@code -W} 算水冷；{@code RDM020-A-B2} 中间的
     * {@code -A-} 是「A 型」结构代号，按库里冷却字段归为自然冷却，不能混进气冷。
     */
    @Test
    void linearFiltersByCoolingStructure() throws Exception {
        seedLinear("AUM4-S1-S", 12, 48);
        seedLinear("AUM4-S1-S-A", 12, 48, 110.0, "气冷");
        seedLinear("AUM4-S1-S-W", 12, 48, 110.0, "水冷");
        seedLinear("RDM020-A-B2", 12, 48, 110.0, "普通");

        Map<String, Object> body = linearRequest();
        body.put("filters", Map.of("structures", List.of(), "coolings", List.of("气冷")));

        JsonNode candidates = call(body, 200).path("data").path("candidates");
        assertThat(candidates).hasSize(1);
        assertThat(candidates.get(0).path("modelName").asText()).isEqualTo("AUM4-S1-S-A");
    }

    /** 「自然冷却」只收不带冷却后缀的型号；`-A-` 结构代号仍归这里。 */
    @Test
    void coolingFilterNaturalKeepsStructuralVariantsOut() throws Exception {
        seedLinear("AUM4-S1-S", 12, 48);
        seedLinear("AUM4-S1-S-A", 12, 48, 110.0, "气冷");
        seedLinear("AUM4-S1-S-W", 12, 48, 110.0, "水冷");
        seedLinear("RDM020-A-B2", 12, 48, 110.0, "普通");

        Map<String, Object> body = linearRequest();
        body.put("filters", Map.of("structures", List.of(), "coolings", List.of("自然冷却")));

        JsonNode candidates = call(body, 200).path("data").path("candidates");
        List<String> models = new ArrayList<>();
        candidates.forEach(node -> models.add(node.path("modelName").asText()));
        assertThat(models).containsExactly("AUM4-S1-S", "RDM020-A-B2");
    }

    @Test
    void filteredOutSelectedMotorKeepsAppliedValues() throws Exception {
        Long motorId = seedLinear("CALC-L1", 12, 48);

        Map<String, Object> body = linearRequest();
        body.put("selectedMotorId", motorId);
        body.put("filters", Map.of("structures", List.of("ACM"), "coolings", List.of()));

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

    private Map<String, Object> rotaryRequest() {
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
        return body;
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
        return seedLinear(modelName, forceCont, forcePeak, 110.0);
    }

    /**
     * @param tMax 最高线圈温度；传 {@code null} 模拟「样册入库」的电机（采集口径不含这一行）
     */
    private Long seedLinear(String modelName, double forceCont, double forcePeak, Double tMax) {
        return seedLinear(modelName, forceCont, forcePeak, tMax, "普通");
    }

    /** 第 69 轮：冷却筛选要用不同冷却字段的电机，故把 cooling 也开放出来。 */
    private Long seedLinear(
            String modelName, double forceCont, double forcePeak, Double tMax, String cooling) {
        MotorEntity motor = commonMotor(modelName, "linear", "无铁芯", cooling);
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
        spec.setTMax(tMax == null ? null : BigDecimal(tMax));
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

    /** 取请求体里的 plan 子对象（baseRequest 每次新建，改它不影响其他用例）。 */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> planOf(Map<String, Object> body) {
        return (Map<String, Object>) body.get("plan");
    }

    /** 采样曲线上的最大力（绝对值），用于验证电机质量也进了采样。 */
    private static double maxSampleForce(JsonNode data) {
        double max = 0;
        for (JsonNode point : data.path("sampling").path("force")) {
            max = Math.max(max, Math.abs(point.path("f").asDouble()));
        }
        return max;
    }
}
