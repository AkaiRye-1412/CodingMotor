package com.motorsizer.app;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A1 / C1–C4 接口契约测试。使用内存 H2，每个用例用不同型号名，避免相互污染。
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:motorsizer_api;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password="
})
@AutoConfigureMockMvc
class MotorApiTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    // ---------------------------------------------------------------- A1

    @Test
    void templatesExposeThreeTypesInOrder() throws Exception {
        mockMvc.perform(get("/api/motor-templates"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("OK"))
                .andExpect(jsonPath("$.data.types[0]").value("linear"))
                .andExpect(jsonPath("$.data.types[1]").value("torque"))
                .andExpect(jsonPath("$.data.types[2]").value("coil"))
                // 直线性能参数 16 项
                .andExpect(jsonPath("$.data.templates.linear.groups[0].fields.length()").value(16))
                .andExpect(jsonPath("$.data.templates.linear.groups[2].optionalAdd").value(true));
    }

    // ---------------------------------------------------------------- C3 + C2

    @Test
    void createLinearThenReadDetailWithDerived() throws Exception {
        String modelName = "TPL-LINEAR-001";
        long id = createMotor(linearPayload(modelName));

        mockMvc.perform(get("/api/motors/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.motorId").value(id))
                .andExpect(jsonPath("$.data.motorType").value("linear"))
                .andExpect(jsonPath("$.data.spec.forceCont").value(12))
                .andExpect(jsonPath("$.data.spec.derived.prated").value(isNumber()))
                .andExpect(jsonPath("$.data.spec.derived.pmax").value(isNumber()))
                .andExpect(jsonPath("$.data.spec.derived.kmDerived").value(isNumber()));
    }

    @Test
    void createCoilMotorUsesOwnSpecTable() throws Exception {
        long id = createMotor(coilPayload("TPL-COIL-001", "0.00045"));

        mockMvc.perform(get("/api/motors/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.motorType").value("coil"))
                .andExpect(jsonPath("$.data.spec.moverMass").value(0.00045))
                .andExpect(jsonPath("$.data.spec.derived.kmDerived").value(isNumber()));
    }

    @Test
    void createTorqueMotorWithBothSpeedThresholds() throws Exception {
        createMotor(torquePayload("TPL-TORQUE-001"));
    }

    // ---------------------------------------------------------------- 校验

    @Test
    void duplicateModelAndConfigReturns409() throws Exception {
        String modelName = "TPL-DUP-001";
        createMotor(linearPayload(modelName));

        mockMvc.perform(post("/api/motors")
                        .contentType(MediaType.APPLICATION_JSON)
                        .characterEncoding(StandardCharsets.UTF_8)
                        .content(objectMapper.writeValueAsString(linearPayload(modelName))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("MOTOR_DUPLICATE"));
    }

    @Test
    void missingRequiredSpecFieldReturnsSpecRequired() throws Exception {
        Map<String, Object> payload = linearPayload("TPL-MISSING-001");
        @SuppressWarnings("unchecked")
        Map<String, Object> spec = (Map<String, Object>) payload.get("spec");
        spec.remove("kth");

        mockMvc.perform(post("/api/motors")
                        .contentType(MediaType.APPLICATION_JSON)
                        .characterEncoding(StandardCharsets.UTF_8)
                        .content(objectMapper.writeValueAsString(payload)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MOTOR_SPEC_REQUIRED"))
                .andExpect(jsonPath("$.data.errors[*].field", hasItem("spec.kth")));
    }

    /**
     * 第 67 轮：最高线圈温度改为选填——样册常只标绝缘等级、不给具体温度上限，
     * 按必填会卡住整批录入。留空仍可保存，只是热校核不出结论（K1 侧按未校核展示）。
     */
    @Test
    void linearWithoutCoilTemperatureIsAccepted() throws Exception {
        Map<String, Object> payload = linearPayload("TPL-NO-TMAX-001");
        @SuppressWarnings("unchecked")
        Map<String, Object> spec = (Map<String, Object>) payload.get("spec");
        spec.remove("tMax");

        long id = createMotor(payload);

        mockMvc.perform(get("/api/motors/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.spec.tMax").doesNotExist());
    }

    /**
     * 第 66 轮：力矩的双转速阈值改为选填——样册口径不统一（AXD 写「@ 持续扭矩（600V）」、
     * AXM 只给一行「最高转速」），按必填会让整批型号在入库预览里「跳过」。
     * 缺此项只跳过准入②并注明「未校核」，不再拒绝保存。
     */
    @Test
    void torqueWithoutSpeedThresholdsIsAcceptedAndSkippedInAdmission() throws Exception {
        Map<String, Object> payload = torquePayload("TPL-TORQUE-NOSPEED");
        @SuppressWarnings("unchecked")
        Map<String, Object> spec = (Map<String, Object>) payload.get("spec");
        spec.remove("maxSpeedPeak");
        spec.remove("maxSpeedCont");

        long id = createMotor(payload);

        mockMvc.perform(get("/api/motors/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.spec.maxSpeedPeak").doesNotExist())
                .andExpect(jsonPath("$.data.spec.maxSpeedCont").doesNotExist());
    }

    @Test
    void fieldOutsideTemplateReturnsTypeMismatch() throws Exception {
        Map<String, Object> payload = linearPayload("TPL-UNKNOWN-001");
        @SuppressWarnings("unchecked")
        Map<String, Object> spec = (Map<String, Object>) payload.get("spec");
        spec.put("torqueCont", 10);

        mockMvc.perform(post("/api/motors")
                        .contentType(MediaType.APPLICATION_JSON)
                        .characterEncoding(StandardCharsets.UTF_8)
                        .content(objectMapper.writeValueAsString(payload)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MOTOR_TYPE_MISMATCH"))
                .andExpect(jsonPath("$.data.errors[*].field", hasItem("spec.torqueCont")));
    }

    @Test
    void derivedObjectInRequestIsRejected() throws Exception {
        Map<String, Object> payload = linearPayload("TPL-DERIVED-001");
        @SuppressWarnings("unchecked")
        Map<String, Object> spec = (Map<String, Object>) payload.get("spec");
        spec.put("derived", Map.of("prated", 1));

        mockMvc.perform(post("/api/motors")
                        .contentType(MediaType.APPLICATION_JSON)
                        .characterEncoding(StandardCharsets.UTF_8)
                        .content(objectMapper.writeValueAsString(payload)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MOTOR_TYPE_MISMATCH"));
    }

    @Test
    void outOfRangeValueIsRejected() throws Exception {
        Map<String, Object> payload = linearPayload("TPL-RANGE-001");
        @SuppressWarnings("unchecked")
        Map<String, Object> spec = (Map<String, Object>) payload.get("spec");
        spec.put("tMax", 400);   // 业务上限 300

        mockMvc.perform(post("/api/motors")
                        .contentType(MediaType.APPLICATION_JSON)
                        .characterEncoding(StandardCharsets.UTF_8)
                        .content(objectMapper.writeValueAsString(payload)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_VALIDATION_FAILED"))
                .andExpect(jsonPath("$.data.errors[*].field", hasItem("spec.tMax")));
    }

    @Test
    void nonPositiveValueIsRejected() throws Exception {
        Map<String, Object> payload = linearPayload("TPL-POSITIVE-001");
        @SuppressWarnings("unchecked")
        Map<String, Object> spec = (Map<String, Object>) payload.get("spec");
        spec.put("iCont", 0);

        mockMvc.perform(post("/api/motors")
                        .contentType(MediaType.APPLICATION_JSON)
                        .characterEncoding(StandardCharsets.UTF_8)
                        .content(objectMapper.writeValueAsString(payload)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_VALIDATION_FAILED"))
                .andExpect(jsonPath("$.data.errors[*].field", hasItem("spec.iCont")));
    }

    // ---------------------------------------------------------------- C4

    @Test
    void updateAllowsRenamingButRejectsTypeChange() throws Exception {
        long id = createMotor(linearPayload("TPL-EDIT-001"));

        Map<String, Object> renamed = linearPayload("TPL-EDIT-001-RENAMED");
        mockMvc.perform(put("/api/motors/{id}", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .characterEncoding(StandardCharsets.UTF_8)
                        .content(objectMapper.writeValueAsString(renamed)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("OK"));

        mockMvc.perform(get("/api/motors/{id}", id))
                .andExpect(jsonPath("$.data.modelName").value("TPL-EDIT-001-RENAMED"));

        Map<String, Object> typeChanged = linearPayload("TPL-EDIT-001-RENAMED");
        typeChanged.put("motorType", "torque");
        typeChanged.put("spec", torqueSpec());

        mockMvc.perform(put("/api/motors/{id}", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .characterEncoding(StandardCharsets.UTF_8)
                        .content(objectMapper.writeValueAsString(typeChanged)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MOTOR_TYPE_MISMATCH"));
    }

    // ---------------------------------------------------------------- C1

    @Test
    void listFiltersByKeywordCaseInsensitively() throws Exception {
        createMotor(linearPayload("TPL-LIST-ABC-001"));

        mockMvc.perform(get("/api/motors")
                        .param("keyword", "tpl-list-abc")
                        .param("page", "1")
                        .param("size", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.page").value(1))
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.items[0].motorId").value(isNumber()));
    }

    @Test
    void clearAllRemovesEveryMotorAcrossTypes() throws Exception {
        createMotor(linearPayload("TPL-CLEAR-L1"));
        createMotor(coilPayload("TPL-CLEAR-C1", "0.05"));
        createMotor(torquePayload("TPL-CLEAR-T1"));

        mockMvc.perform(delete("/api/motors"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("OK"));

        mockMvc.perform(get("/api/motors").param("page", "1").param("size", "100"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(0));
    }

    // ---------------------------------------------------------------- 夹具

    private long createMotor(Map<String, Object> payload) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/motors")
                        .contentType(MediaType.APPLICATION_JSON)
                        .characterEncoding(StandardCharsets.UTF_8)
                        .content(objectMapper.writeValueAsString(payload)))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode node = objectMapper.readTree(result.getResponse().getContentAsString());
        return node.path("data").path("motorId").asLong();
    }

    private Map<String, Object> linearPayload(String modelName) {
        Map<String, Object> payload = commonPayload(modelName, "linear", "无铁芯", "普通", null);

        Map<String, Object> spec = new LinkedHashMap<>();
        spec.put("forceCont", 12);
        spec.put("forcePeak", 48);
        spec.put("kf", 5.6);
        spec.put("ke", 3.9);
        spec.put("r25", 8.8);
        spec.put("iCont", 2.14);
        spec.put("iPeak", 8.57);
        spec.put("tMax", 110);
        spec.put("kth", 1.1);
        spec.put("uBus", 400);
        payload.put("spec", spec);
        return payload;
    }

    private Map<String, Object> coilPayload(String modelName, String moverMassKg) {
        Map<String, Object> payload = commonPayload(modelName, "coil", "音圈", "普通", null);

        Map<String, Object> spec = new LinkedHashMap<>();
        spec.put("forceCont", 20);
        spec.put("forcePeak", 60);
        spec.put("kf", 8.5);
        spec.put("ke", 3.2);
        spec.put("r25", 6.4);
        spec.put("iCont", 2.5);
        spec.put("iPeak", 7.0);
        spec.put("tMax", 100);
        spec.put("kth", 2.0);
        spec.put("uBus", 48);
        spec.put("moverMass", Double.parseDouble(moverMassKg));
        payload.put("spec", spec);
        return payload;
    }

    private Map<String, Object> torquePayload(String modelName) {
        Map<String, Object> payload = commonPayload(modelName, "torque", "有铁芯", "普通", "标准");
        payload.put("spec", torqueSpec());
        return payload;
    }

    private Map<String, Object> torqueSpec() {
        Map<String, Object> spec = new LinkedHashMap<>();
        spec.put("torqueCont", 4.0);
        spec.put("torquePeak", 12.0);
        spec.put("kt", 0.52);
        spec.put("ke", 3.2);
        spec.put("r25", 4.2);
        spec.put("iCont", 7.7);
        spec.put("iPeak", 23.0);
        spec.put("tMax", 120);
        spec.put("kth", 1.2);
        spec.put("uBus", 311);
        spec.put("maxSpeedPeak", 2000);
        spec.put("maxSpeedCont", 800);
        return spec;
    }

    private Map<String, Object> commonPayload(String modelName, String motorType,
                                              String structure, String cooling, String bore) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("modelName", modelName);
        payload.put("configName", "默认");
        payload.put("motorType", motorType);
        payload.put("structure", structure);
        payload.put("cooling", cooling);
        payload.put("bore", bore);
        payload.put("entryMethod", "手工录入");
        return payload;
    }

    private static org.hamcrest.Matcher<Object> isNumber() {
        return org.hamcrest.Matchers.instanceOf(Number.class);
    }
}
