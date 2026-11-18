package com.motorsizer.app;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 样册识别 I1–I13 接口契约 + 端到端（06 §7/§8 P0）。
 *
 * <p>用 PDFBox 嵌入 Noto Sans SC 生成中文样册 PDF，走通「上传 → 解析 → 草稿 → 提交入库」。
 * imports 目录指向 target 下临时目录，避免触碰真实 %APPDATA% 数据。
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:motorsizer_import;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "motorsizer.imports.dir=target/import-test-jobs"
})
@AutoConfigureMockMvc
class ImportApiTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @BeforeAll
    static void cleanWorkspace() throws Exception {
        Path dir = Path.of("target/import-test-jobs");
        if (Files.exists(dir)) {
            try (var walk = Files.walk(dir)) {
                walk.sorted(java.util.Comparator.reverseOrder())
                        .forEach(p -> {
                            try { Files.deleteIfExists(p); } catch (Exception ignored) {}
                        });
            }
        }
    }

    // ---------------------------------------------------------------- 端到端

    @Test
    void uploadRecognizeCommitCreatesMotor() throws Exception {
        byte[] pdf = buildDatasheetPdf(List.of(
                "型号: ADR-END2END-001",
                "持续推力 120 N",
                "峰值推力 480 N",
                "力常数 5.6 N/Arms",
                "反电势常数 3.9 Vpeak/(m/s)",
                "相间电阻 @25°C 8.8 Ω",
                "持续电流 2.14 Arms",
                "峰值电流 8.57 Arms",
                "最高线圈温度 110 °C",
                "热耗散常数 1.1 W/°C",
                "最高母线电压 400 Vdc"));

        String jobId = createJob(pdf, "sample.pdf");

        // I1 已自动解析，草稿已生成；从任务详情取 draftId（不再需要显式调 I7）
        MvcResult detail = mockMvc.perform(get("/api/imports/{jobId}", jobId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.pages[0].status").value("RECOGNIZED"))
                .andExpect(jsonPath("$.data.drafts.length()").value(1))
                .andExpect(jsonPath("$.data.drafts[0].request.motorType").value("linear"))
                .andExpect(jsonPath("$.data.drafts[0].request.spec.forceCont").value(120))
                .andReturn();
        String draftId = objectMapper.readTree(detail.getResponse().getContentAsString())
                .path("data").path("drafts").get(0).path("draftId").asText();

        // I8 列表
        mockMvc.perform(get("/api/imports/{jobId}/drafts", jobId))
                .andExpect(jsonPath("$.data.length()").value(1));

        // I13 勾选
        mockMvc.perform(post("/api/imports/{jobId}/drafts/{draftId}/checked", jobId, draftId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"checked\":true}"))
                .andExpect(status().isOk());

        // I12 提交
        MvcResult commit = mockMvc.perform(post("/api/imports/{jobId}/commit", jobId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.succeeded").value(1))
                .andExpect(jsonPath("$.data.failed").value(0))
                .andReturn();
        long motorId = objectMapper.readTree(commit.getResponse().getContentAsString())
                .path("data").path("items").get(0).path("motorId").asLong();

        // 验证电机已入库
        mockMvc.perform(get("/api/motors/{id}", motorId))
                .andExpect(jsonPath("$.data.modelName").value("ADR-END2END-001"))
                .andExpect(jsonPath("$.data.entryMethod").value("识别草稿"))
                .andExpect(jsonPath("$.data.spec.forceCont").value(120));

        // I4 清理
        mockMvc.perform(delete("/api/imports/{jobId}", jobId)).andExpect(status().isOk());
    }

    // ---------------------------------------------------------------- 分支

    @Test
    void imagePageReturnsOcrRequired() throws Exception {
        MockMultipartFile img = new MockMultipartFile("files", "scan.png", "image/png", new byte[]{1, 2, 3});
        MvcResult created = mockMvc.perform(multipart("/api/imports").file(img))
                .andExpect(status().isCreated()).andReturn();
        String jobId = jobId(created);
        String pageId = firstPageId(jobId);

        // I7 图片页：返回 200 + 空 drafts，页面状态置 OCR_REQUIRED
        mockMvc.perform(post("/api/imports/{jobId}/pages/{pageId}/recognize", jobId, pageId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(0));

        // I6 取文本：图片页无文本层 → 400
        mockMvc.perform(get("/api/imports/{jobId}/pages/{pageId}/text", jobId, pageId))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("IMPORT_OCR_REQUIRED"));

        mockMvc.perform(delete("/api/imports/{jobId}", jobId)).andExpect(status().isOk());
    }

    @Test
    void commitWithoutCheckReturnsNothingToCommit() throws Exception {
        byte[] pdf = buildDatasheetPdf(List.of("型号: ADR-NOCHECK-001", "持续推力 120 N", "峰值推力 480 N"));
        String jobId = createJob(pdf, "x.pdf");
        String pageId = firstPageId(jobId);
        mockMvc.perform(post("/api/imports/{jobId}/pages/{pageId}/recognize", jobId, pageId))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/imports/{jobId}/commit", jobId))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("IMPORT_NOTHING_TO_COMMIT"));

        mockMvc.perform(delete("/api/imports/{jobId}", jobId)).andExpect(status().isOk());
    }

    @Test
    void duplicateDraftCommitReportsFailure() throws Exception {
        // 先直接建一台同名电机
        Map<String, Object> existing = linearPayload("ADR-DUP-001");
        mockMvc.perform(post("/api/motors").contentType(MediaType.APPLICATION_JSON)
                        .characterEncoding(StandardCharsets.UTF_8)
                        .content(objectMapper.writeValueAsString(existing)))
                .andExpect(status().isCreated());

        byte[] pdf = buildDatasheetPdf(List.of(
                "型号: ADR-DUP-001",
                "持续推力 120 N", "峰值推力 480 N", "力常数 5.6 N/Arms",
                "反电势常数 3.9", "相间电阻 8.8", "持续电流 2.14", "峰值电流 8.57",
                "最高线圈温度 110", "热耗散常数 1.1", "最高母线电压 400"));
        String jobId = createJob(pdf, "dup.pdf");
        String pageId = firstPageId(jobId);

        MvcResult rec = mockMvc.perform(post("/api/imports/{jobId}/pages/{pageId}/recognize", jobId, pageId))
                .andExpect(status().isOk()).andReturn();
        String draftId = objectMapper.readTree(rec.getResponse().getContentAsString())
                .path("data").get(0).path("draftId").asText();

        mockMvc.perform(post("/api/imports/{jobId}/drafts/{draftId}/checked", jobId, draftId)
                .contentType(MediaType.APPLICATION_JSON).content("{\"checked\":true}"))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/imports/{jobId}/commit", jobId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.failed").value(1))
                .andExpect(jsonPath("$.data.succeeded").value(0))
                .andExpect(jsonPath("$.data.items[0].errorCode").value("MOTOR_DUPLICATE"));

        mockMvc.perform(delete("/api/imports/{jobId}", jobId)).andExpect(status().isOk());
    }

    @Test
    void listJobsReturnsSummaries() throws Exception {
        byte[] pdf = buildDatasheetPdf(List.of("型号: ADR-LIST-001", "持续推力 120 N"));
        createJob(pdf, "list.pdf");
        mockMvc.perform(get("/api/imports"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].jobId").exists());
    }

    // ---------------------------------------------------------------- 夹具

    private String createJob(byte[] pdf, String filename) throws Exception {
        MockMultipartFile file = new MockMultipartFile("files", filename, "application/pdf", pdf);
        MvcResult result = mockMvc.perform(multipart("/api/imports").file(file))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.jobId").exists())
                .andExpect(jsonPath("$.data.pages[0]").exists())
                // I1 已自动解析：PDF 页状态应为 RECOGNIZED（不再 PENDING）
                .andExpect(jsonPath("$.data.pages[0].status").value("RECOGNIZED"))
                .andReturn();
        return jobId(result);
    }

    private String jobId(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString()).path("data").path("jobId").asText();
    }

    private String firstPageId(String jobId) throws Exception {
        MvcResult detail = mockMvc.perform(get("/api/imports/{jobId}", jobId))
                .andExpect(status().isOk()).andReturn();
        return objectMapper.readTree(detail.getResponse().getContentAsString())
                .path("data").path("pages").get(0).path("pageId").asText();
    }

    private Map<String, Object> linearPayload(String modelName) {
        Map<String, Object> spec = new LinkedHashMap<>();
        spec.put("forceCont", 12); spec.put("forcePeak", 48); spec.put("kf", 5.6);
        spec.put("ke", 3.9); spec.put("r25", 8.8); spec.put("iCont", 2.14);
        spec.put("iPeak", 8.57); spec.put("tMax", 110); spec.put("kth", 1.1); spec.put("uBus", 400);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("modelName", modelName);
        payload.put("configName", "默认");
        payload.put("motorType", "linear");
        payload.put("structure", "无铁芯");
        payload.put("cooling", "普通");
        payload.put("entryMethod", "手工录入");
        payload.put("spec", spec);
        return payload;
    }

    /** 用 PDFBox 嵌入系统 CJK 字体（SimHei）生成中文样册 PDF。 */
    private byte[] buildDatasheetPdf(List<String> lines) throws Exception {
        try (PDDocument doc = new PDDocument();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PDPage page = new PDPage(PDRectangle.A4);
            doc.addPage(page);
            // 用系统非变量 CJK 字体，避免 NotoSansSC-VF 在 PDFBox 2.0.x 的 ToUnicode 反向映射问题
            PDType0Font font = PDType0Font.load(doc, java.nio.file.Path.of("C:/Windows/Fonts/simhei.ttf").toFile());
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.beginText();
                cs.setLeading(18f);
                cs.setFont(font, 11);
                cs.newLineAtOffset(40, 800);
                for (String line : lines) {
                    cs.showText(line);
                    cs.newLine();
                }
                cs.endText();
            }
            doc.save(out);
            return out.toByteArray();
        }
    }
}
