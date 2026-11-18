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
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** D1 `POST /api/reports/pdf` 契约测试。 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:motorsizer_report;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password="
})
@AutoConfigureMockMvc
class ReportApiTest {

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

    @Test
    void exportPdfReturnsPdfWithChineseText() throws Exception {
        Long motorId = seedLinear("REPORT-L1");

        MvcResult result = mockMvc.perform(post("/api/reports/pdf")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(reportRequest(validPlan(motorId)))))
                .andExpect(status().isOk())
                .andReturn();

        byte[] pdf = result.getResponse().getContentAsByteArray();
        assertThat(result.getResponse().getContentType()).isEqualTo("application/pdf");
        assertThat(pdf.length).isGreaterThan(10_000);
        assertThat(new String(pdf, 0, 5, StandardCharsets.US_ASCII)).isEqualTo("%PDF-");
        assertThat(result.getResponse().getHeader("Content-Disposition"))
                .contains("attachment")
                .contains(".pdf");

        String text = extractText(pdf);
        assertThat(text).contains("电机选型报告");
        assertThat(text).contains("测试项目");
        assertThat(text).contains("推荐清单");
        assertThat(text).contains("运动曲线");
    }

    @Test
    void emptyPlanListReturnsReportNoPlan() throws Exception {
        JsonNode root = export(reportRequest(null), 400);
        assertThat(root.path("code").asText()).isEqualTo("REPORT_NO_PLAN");
    }

    @Test
    void allUncomputablePlansReturnReportAllUncomputable() throws Exception {
        Map<String, Object> body = reportRequest(null);
        body.put("plans", List.of(uncomputablePlan()));

        JsonNode root = export(body, 400);
        assertThat(root.path("code").asText()).isEqualTo("REPORT_ALL_UNCOMPUTABLE");
    }

    @Test
    void partialFailureStillExportsPdfWithReason() throws Exception {
        Long motorId = seedLinear("REPORT-L1");
        Map<String, Object> body = reportRequest(null);
        body.put("plans", List.of(validPlan(motorId), uncomputablePlan()));

        MvcResult result = mockMvc.perform(post("/api/reports/pdf")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andReturn();

        byte[] pdf = result.getResponse().getContentAsByteArray();
        assertThat(pdf.length).isGreaterThan(10_000);

        String text = extractText(pdf);
        assertThat(text).contains("该方案无法计算");
        // 报告中必须给出具体原因，而不是泛化的「参数校验失败」
        assertThat(text).contains("点到点模式必须给出大于 0 的运动时间");
    }

    @Test
    void blankProjectNameIsRejected() throws Exception {
        Map<String, Object> body = reportRequest(null);
        body.put("projectName", "  ");
        body.put("plans", List.of(uncomputablePlan()));
        JsonNode root = export(body, 400);
        assertThat(root.path("code").asText()).isEqualTo("COMMON_VALIDATION_FAILED");
        List<String> fields = new ArrayList<>();
        root.path("data").path("errors").forEach(error -> fields.add(error.path("field").asText()));
        assertThat(fields).contains("projectName");
    }

    // ---------------------------------------------------------------- 请求构造

    private Map<String, Object> reportRequest(Map<String, Object> validPlan) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("projectName", "测试项目");
        body.put("axisName", "Axis 1");
        body.put("reportDate", "2026-11-18");
        body.put("axisKind", "linear");
        body.put("customerName", "测试客户");
        body.put("workingConditionNote", "验证报告导出");
        body.put("settings", Map.of(
                "mode", "pointToPoint",
                "smooth", Map.of("enabled", false, "tauMs", 16),
                "margin", Map.of("lowerPercent", 30.0, "upperPercent", 300.0, "upperEnabled", false)));
        body.put("plans", validPlan == null ? List.of() : List.of(validPlan));
        return body;
    }

    private Map<String, Object> validPlan(Long motorId) {
        Map<String, Object> plan = new LinkedHashMap<>();
        plan.put("load", 2.0);
        plan.put("distance", 1.0);
        plan.put("time", 1.0);
        plan.put("dwell", 0.3);
        plan.put("tiltDeg", 0.0);
        plan.put("ambientTemp", 22.5);
        plan.put("supplyVoltage", 311.0);

        Map<String, Object> item = new LinkedHashMap<>();
        item.put("planNo", 1);
        item.put("plan", plan);
        item.put("shape", "trapezoid");
        item.put("selectedMotorId", motorId);
        return item;
    }

    private Map<String, Object> uncomputablePlan() {
        Map<String, Object> plan = new LinkedHashMap<>();
        plan.put("load", 2.0);
        plan.put("distance", 1.0);

        Map<String, Object> item = new LinkedHashMap<>();
        item.put("plan", plan);
        item.put("shape", "trapezoid");
        return item;
    }

    private JsonNode export(Map<String, Object> body, int expectedStatus) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/reports/pdf")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().is(expectedStatus))
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private String extractText(byte[] pdf) throws Exception {
        try (PDDocument document = PDDocument.load(pdf)) {
            // 字体子集可能把部分常用字映射为兼容码位，比对前统一归一化
            return new PDFTextStripper().getText(document).replace(' ', ' ');
        }
    }

    // ---------------------------------------------------------------- 夹具

    private Long seedLinear(String modelName) {
        MotorEntity motor = new MotorEntity();
        motor.setModelName(modelName);
        motor.setConfigName("默认");
        motor.setMotorType("linear");
        motor.setStructure("无铁芯");
        motor.setCooling("普通");
        motor.setEntryMethod("手工录入");
        motor.setEnteredOn(LocalDate.of(2026, 11, 18));
        motor.setCreatedAt(NOW);
        motor.setUpdatedAt(NOW);
        motorMapper.insert(motor);

        MotorLinearSpecEntity spec = new MotorLinearSpecEntity();
        spec.setMotorId(motor.getId());
        spec.setForceCont(dec(12));
        spec.setForcePeak(dec(48));
        spec.setKf(dec(5.6));
        spec.setKe(dec(3.9));
        spec.setR25(dec(8.8));
        spec.setICont(dec(2.14));
        spec.setIPeak(dec(8.57));
        spec.setTMax(dec(110));
        spec.setKth(dec(1.1));
        spec.setUBus(dec(400));
        spec.setMaxSpeedPeak(dec(5.0));
        spec.setMaxSpeedCont(dec(6.0));
        spec.setCreatedAt(NOW);
        spec.setUpdatedAt(NOW);
        linearSpecMapper.insert(spec);
        return motor.getId();
    }

    private static java.math.BigDecimal dec(double value) {
        return java.math.BigDecimal.valueOf(value);
    }
}
