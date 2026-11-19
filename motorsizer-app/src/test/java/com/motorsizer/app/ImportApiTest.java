package com.motorsizer.app;

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
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 样册 PDF → Excel → 入库 接口契约（06 v2 §6、§6.1）。
 *
 * <p>用 PDFBox 现造「符号 + 单位 + 串联/并联」样册页，走通
 * 「上传 → 解析 → 生成 xlsx → 下载 → 入库（新增/覆盖）」，并覆盖文件类型与任务不存在两类错误分支。
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
                walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                    try {
                        Files.deleteIfExists(p);
                    } catch (Exception ignored) {
                        // 临时目录清理失败不影响断言
                    }
                });
            }
        }
    }

    // ---------------------------------------------------------------- 主链路

    @Test
    void uploadConvertsAndExports() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "AXD120.pdf",
                "application/pdf", samplePdf());

        MvcResult created = mockMvc.perform(multipart("/api/imports").file(file))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value("OK"))
                .andExpect(jsonPath("$.data.jobId").exists())
                .andExpect(jsonPath("$.data.status").value("READY"))
                .andExpect(jsonPath("$.data.files[0].name").value("AXD120.pdf"))
                .andExpect(jsonPath("$.data.files[0].pages").value(1))
                .andExpect(jsonPath("$.data.stats.tableCount").value(1))
                .andExpect(jsonPath("$.data.stats.modelCount").value(1))
                .andExpect(jsonPath("$.data.stats.dataRows").value(8))   // 4 参数 × 串联/并联
                .andExpect(jsonPath("$.data.stats.excelName").value("AXD120_参数表.xlsx"))
                .andReturn();
        String jobId = objectMapper.readTree(created.getResponse().getContentAsString())
                .path("data").path("jobId").asText();

        // 任务详情
        mockMvc.perform(get("/api/imports/{jobId}", jobId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.stats.tableCount").value(1));

        // 任务列表含该任务
        mockMvc.perform(get("/api/imports"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].jobId").value(jobId))
                .andExpect(jsonPath("$.data[0].fileName").value("AXD120.pdf"));

        // 下载 xlsx（zip 容器，魔数 PK）
        MvcResult exported = mockMvc.perform(get("/api/imports/{jobId}/export", jobId))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type",
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .andReturn();
        byte[] body = exported.getResponse().getContentAsByteArray();
        assertThat(body.length).isGreaterThan(1000);
        assertThat(body[0]).isEqualTo((byte) 'P');
        assertThat(body[1]).isEqualTo((byte) 'K');
        assertThat(exported.getResponse().getHeader("Content-Disposition"))
                .contains("attachment")
                .contains("filename");

        // 删除任务后详情 404
        mockMvc.perform(delete("/api/imports/{jobId}", jobId)).andExpect(status().isOk());
        mockMvc.perform(get("/api/imports/{jobId}", jobId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("IMPORT_JOB_NOT_FOUND"));
    }

    // ---------------------------------------------------------------- 入库

    /**
     * 入库（06 §6.1）：Excel 里的电机写进电机库；再入库一次同主键 = 整体覆盖，不重复插入。
     *
     * <p>数据源是 Excel 而非内存解析结果——先落 xlsx，再读回来写库。
     */
    @Test
    void commitsExcelIntoMotorLibraryAndOverwritesOnRepeat() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "TQ120.pdf",
                "application/pdf", torqueCatalogPdf());
        MvcResult created = mockMvc.perform(multipart("/api/imports").file(file))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.status").value("READY"))
                .andReturn();
        String jobId = objectMapper.readTree(created.getResponse().getContentAsString())
                .path("data").path("jobId").asText();

        mockMvc.perform(post("/api/imports/{jobId}/commit", jobId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("OK"))
                .andExpect(jsonPath("$.data.total").value(2))
                .andExpect(jsonPath("$.data.created").value(2))
                .andExpect(jsonPath("$.data.updated").value(0))
                .andExpect(jsonPath("$.data.skipped").value(0))
                .andExpect(jsonPath("$.data.items[0].modelName").value("TQ120-71-S"))
                .andExpect(jsonPath("$.data.items[0].configName").value("默认"))
                .andExpect(jsonPath("$.data.items[0].motorType").value("torque"))
                .andExpect(jsonPath("$.data.items[0].status").value("CREATED"));

        // 再点一次「入库」：主键相同 → 覆盖，库里仍然只有 2 台
        mockMvc.perform(post("/api/imports/{jobId}/commit", jobId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.created").value(0))
                .andExpect(jsonPath("$.data.updated").value(2));

        mockMvc.perform(get("/api/motors").param("keyword", "TQ120-71"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(2));

        mockMvc.perform(delete("/api/imports/{jobId}", jobId)).andExpect(status().isOk());
    }

    /** 任务不存在时入库报 404；任务被删/产物缺失同样 404，不产生 500。 */
    @Test
    void commitUnknownJobReturnsNotFound() throws Exception {
        mockMvc.perform(post("/api/imports/{jobId}/commit", "imp-不存在"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("IMPORT_JOB_NOT_FOUND"));
    }

    // ---------------------------------------------------------------- 错误分支

    /** 06 v1 遗留任务目录（status=PARSED 等已删除的枚举值）应被静默忽略，不产生 500。 */
    @Test
    void legacyJobDirectoryIsIgnored() throws Exception {
        Path dir = Path.of("target/import-test-jobs/imp-20260101-000000-0001");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("job.json"),
                "{\"jobId\":\"imp-20260101-000000-0001\",\"status\":\"PARSED\"}");

        mockMvc.perform(get("/api/imports"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[?(@.jobId == 'imp-20260101-000000-0001')]").isEmpty());

        mockMvc.perform(get("/api/imports/{jobId}", "imp-20260101-000000-0001"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("IMPORT_JOB_NOT_FOUND"));
    }

    @Test
    void rejectsNonPdf() throws Exception {
        MockMultipartFile png = new MockMultipartFile("file", "scan.png",
                "image/png", new byte[]{1, 2, 3});
        mockMvc.perform(multipart("/api/imports").file(png))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("IMPORT_FILE_REJECTED"));
    }

    @Test
    void rejectsEmptyFile() throws Exception {
        MockMultipartFile empty = new MockMultipartFile("file", "empty.pdf",
                "application/pdf", new byte[0]);
        mockMvc.perform(multipart("/api/imports").file(empty))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("IMPORT_FILE_REJECTED"));
    }

    @Test
    void unknownJobReturnsNotFound() throws Exception {
        mockMvc.perform(get("/api/imports/{jobId}", "imp-不存在"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("IMPORT_JOB_NOT_FOUND"));
        mockMvc.perform(get("/api/imports/{jobId}/export", "imp-不存在"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("IMPORT_JOB_NOT_FOUND"));
    }

    // ---------------------------------------------------------------- 夹具

    /**
     * 一页力矩样册式表格：12 个必填参数齐备（{@code Tcn/Tpk/Kt/Ke/R25/Icn/Ipk/Tmax/Kth/Ubus} +
     * 两行 {@code 最高转速}），串联/并联各一列 → Excel 出 {@code -S} / {@code -P} 两个型号。
     */
    private byte[] torqueCatalogPdf() throws Exception {
        try (PDDocument doc = new PDDocument();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PDPage page = new PDPage(PDRectangle.A4);
            doc.addPage(page);
            PDType0Font font = PDType0Font.load(doc, Path.of("C:/Windows/Fonts/simhei.ttf").toFile());
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                put(cs, font, 60, 760, "TQ120-71");
                put(cs, font, 150, 730, "符号");
                put(cs, font, 200, 730, "单位");
                put(cs, font, 250, 730, "串联");
                put(cs, font, 300, 730, "并联");
                String[][] rows = {
                        {"持续转矩@100°C", "Tcn", "Nm", "3.4", "1.7"},
                        {"峰值转矩", "Tpk", "Nm", "10.0", "5.0"},
                        {"转矩常数", "Kt", "Nm/Arms", "3.0", "1.5"},
                        {"反电势常数", "Ke", "Vpeak/rpm", "0.26", "0.13"},
                        {"相间电阻", "R25", "Ohm", "1.92", "0.96"},
                        {"持续电流@100°C", "Icn", "Arms", "3.8", "1.9"},
                        {"峰值电流", "Ipk", "Arms", "12.2", "6.1"},
                        {"最高线圈温度", "Tmax", "C", "100", "100"},
                        {"热耗散常数", "Kth", "W/C", "0.715", "0.715"},
                        {"最高母线电压", "Ubus", "Vdc", "600", "600"},
                        {"最高转速@峰值转矩", "Nmax", "rpm", "1700", "1700"},
                        {"最高转速@持续转矩", "Nmax", "rpm", "2000", "2000"}};
                int y = 716;
                for (String[] row : rows) {
                    put(cs, font, 60, y, row[0]);
                    put(cs, font, 150, y, row[1]);
                    put(cs, font, 200, y, row[2]);
                    put(cs, font, 250, y, row[3]);
                    put(cs, font, 300, y, row[4]);
                    y -= 14;
                }
            }
            doc.save(out);
            return out.toByteArray();
        }
    }

    /** 一页样册式表格：型号行 + 表头（符号/单位/串联/并联）+ 4 行参数。 */
    private byte[] samplePdf() throws Exception {
        try (PDDocument doc = new PDDocument();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PDPage page = new PDPage(PDRectangle.A4);
            doc.addPage(page);
            PDType0Font font = PDType0Font.load(doc, Path.of("C:/Windows/Fonts/simhei.ttf").toFile());
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                put(cs, font, 60, 760, "AXD120-71");
                put(cs, font, 150, 730, "符号");
                put(cs, font, 200, 730, "单位");
                put(cs, font, 250, 730, "串联");
                put(cs, font, 300, 730, "并联");
                String[][] rows = {
                        {"持续转矩", "Tcn", "Nm", "3.4", "3.4"},
                        {"峰值转矩", "Tpk", "Nm", "10.0", "10.0"},
                        {"转矩常数", "Kt", "Nm/Arms", "3.0", "1.5"},
                        {"最高转速", "Nmax", "rpm", "1700", "2000"}};
                int y = 716;
                for (String[] row : rows) {
                    put(cs, font, 60, y, row[0]);
                    put(cs, font, 150, y, row[1]);
                    put(cs, font, 200, y, row[2]);
                    put(cs, font, 250, y, row[3]);
                    put(cs, font, 300, y, row[4]);
                    y -= 14;
                }
            }
            doc.save(out);
            return out.toByteArray();
        }
    }

    private void put(PDPageContentStream cs, PDType0Font font, float x, float y, String value)
            throws Exception {
        cs.beginText();
        cs.setFont(font, 8);
        cs.newLineAtOffset(x, y);
        cs.showText(value);
        cs.endText();
    }
}
