package com.motorsizer.app.importing.recognize;

import com.motorsizer.app.importing.Draft;
import com.motorsizer.app.importing.PageStatus;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 页面解析器单测（06 §5）：用构造的文本行验证单配置 / 多列 / 空页 / 类型判定，
 * 与 PDF 提取层解耦——PDF 文本提取走标准 PDFBox，在此不重复验证。
 */
class PageRecognizerTest {

    private final PageRecognizer recognizer = new PageRecognizer(new FieldDictionary());

    @Test
    void singleConfigLinearProducesOneDraftWithAllRequired() {
        List<String> lines = List.of(
                "型号: ADR110-B136",
                "持续推力 120 N",
                "峰值推力 480 N",
                "力常数 5.6 N/Arms",
                "反电势常数 3.9 Vpeak/(m/s)",
                "相间电阻 @25°C 8.8 Ω",
                "持续电流 2.14 Arms",
                "峰值电流 8.57 Arms",
                "最高线圈温度 110 °C",
                "热耗散常数 1.1 W/°C",
                "最高母线电压 400 Vdc");

        PageRecognizer.Result result = recognizer.recognize(new PageText(lines), "f1p1", 1, "ADR110.pdf");

        assertThat(result.status()).isEqualTo(PageStatus.RECOGNIZED);
        assertThat(result.drafts()).hasSize(1);
        Draft draft = result.drafts().get(0);
        assertThat(draft.request().modelName()).isEqualTo("ADR110-B136");
        assertThat(draft.request().motorType()).isEqualTo("linear");
        assertThat(draft.request().configName()).isEqualTo("默认");
        assertThat(draft.request().entryMethod()).isEqualTo("识别草稿");
        Map<String, Object> spec = draft.request().spec();
        assertThat(spec.get("forceCont")).isEqualTo(120.0);
        assertThat(spec.get("r25")).isEqualTo(8.8);
        assertThat(spec.get("iCont")).isEqualTo(2.14);
        assertThat(spec.get("uBus")).isEqualTo(400.0);
        assertThat(draft.confidence()).isGreaterThan(0.6);
        // 原始值留存
        assertThat(draft.rawFields().get("r25").value()).isEqualTo("8.8");
    }

    @Test
    void multiColumnProducesOneDraftPerConfig() {
        List<String> lines = List.of(
                "型号: AVM-S2",
                "默认  串联",
                "持续推力  20  40",
                "峰值推力  60  120",
                "力常数  8.5  17",
                "反电势常数  3.9  7.8",
                "相间电阻 @25°C  6.4  12.8",
                "持续电流  2.5  5.0",
                "峰值电流  7.0  14.0",
                "最高线圈温度  100  100",
                "热耗散常数  2.0  2.0",
                "最高母线电压  48  48");

        PageRecognizer.Result result = recognizer.recognize(new PageText(lines), "f1p1", 1, "AVM.pdf");

        assertThat(result.status()).isEqualTo(PageStatus.RECOGNIZED);
        assertThat(result.drafts()).hasSize(2);
        Draft def = result.drafts().get(0);
        Draft series = result.drafts().get(1);
        assertThat(def.request().configName()).isEqualTo("默认");
        assertThat(series.request().configName()).isEqualTo("串联");
        assertThat(def.request().modelName()).isEqualTo("AVM-S2");
        assertThat(series.request().modelName()).isEqualTo("AVM-S2");
        assertThat(def.request().spec().get("forceCont")).isEqualTo(20.0);
        assertThat(series.request().spec().get("forceCont")).isEqualTo(40.0);
        assertThat(def.request().spec().get("r25")).isEqualTo(6.4);
        assertThat(series.request().spec().get("r25")).isEqualTo(12.8);
    }

    @Test
    void noKnownFieldsYieldsEmpty() {
        PageRecognizer.Result result = recognizer.recognize(
                new PageText(List.of("一些无关文字", "另一行说明")), "f1p1", 1, "x.pdf");
        assertThat(result.status()).isEqualTo(PageStatus.EMPTY);
        assertThat(result.drafts()).isEmpty();
    }

    @Test
    void torqueFieldsDetectedAsTorqueType() {
        List<String> lines = List.of(
                "型号: TTM-100",
                "持续转矩 4.0 Nm",
                "峰值转矩 12.0 Nm",
                "转矩常数 0.52 Nm/Arms",
                "相间电阻 4.2 Ω",
                "持续电流 7.7 Arms",
                "峰值电流 23.0 Arms",
                "最高线圈温度 120 °C",
                "热耗散常数 1.2 W/°C",
                "最高母线电压 311 Vdc",
                "最高转速 @峰值转矩 2000 rpm",
                "最高转速 @持续转矩 800 rpm");

        PageRecognizer.Result result = recognizer.recognize(new PageText(lines), "f1p1", 1, "TTM.pdf");
        assertThat(result.status()).isEqualTo(PageStatus.RECOGNIZED);
        assertThat(result.drafts()).hasSize(1);
        assertThat(result.drafts().get(0).request().motorType()).isEqualTo("torque");
        assertThat(result.drafts().get(0).request().spec().get("maxSpeedPeak")).isEqualTo(2000.0);
        assertThat(result.drafts().get(0).request().spec().get("maxSpeedCont")).isEqualTo(800.0);
    }

    @Test
    void emptyTextYieldsEmpty() {
        PageRecognizer.Result result = recognizer.recognize(new PageText(List.of()), "f1p1", 1, "x.pdf");
        assertThat(result.status()).isEqualTo(PageStatus.EMPTY);
    }

    @Test
    void englishLabelsMatchViaAliases() {
        // 全英文样册：验证英文别名（Continuous force / Force constant / Phase resistance 等）命中
        List<String> lines = List.of(
                "Model: GLM-S-200",
                "Continuous force 120 N",
                "Peak force 360 N",
                "Force constant 8.5 N/Arms",
                "Back EMF constant 3.9 V/(m/s)",
                "Phase resistance 4.4 Ω",
                "Continuous current 2.14 Arms",
                "Peak current 6.4 Arms",
                "Max. coil temp 110 °C",
                "Thermal dissipation 1.1 W/°C",
                "Bus voltage 400 Vdc");

        PageRecognizer.Result result = recognizer.recognize(new PageText(lines), "f1p1", 1, "GLM.pdf");

        assertThat(result.status()).isEqualTo(PageStatus.RECOGNIZED);
        assertThat(result.drafts()).hasSize(1);
        Draft draft = result.drafts().get(0);
        assertThat(draft.request().modelName()).isEqualTo("GLM-S-200");
        assertThat(draft.request().motorType()).isEqualTo("linear");
        assertThat(draft.request().spec().get("forceCont")).isEqualTo(120.0);
        assertThat(draft.request().spec().get("forcePeak")).isEqualTo(360.0);
        assertThat(draft.request().spec().get("kf")).isEqualTo(8.5);
        assertThat(draft.request().spec().get("r25")).isEqualTo(4.4);
        assertThat(draft.request().spec().get("iCont")).isEqualTo(2.14);
        assertThat(draft.request().spec().get("uBus")).isEqualTo(400.0);
    }

    @Test
    void singleFieldStillProducesDraft() {
        // 阈值=1：仅命中 1 个字段也应生成草稿（供用户在表格里补全其余字段）
        List<String> lines = List.of(
                "型号: ADR-MIN-001",
                "持续推力 80 N",
                "此处无任何已知字段",
                "另一行无关说明文字");

        PageRecognizer.Result result = recognizer.recognize(new PageText(lines), "f1p1", 1, "min.pdf");

        assertThat(result.status()).isEqualTo(PageStatus.RECOGNIZED);
        assertThat(result.drafts()).hasSize(1);
        Draft draft = result.drafts().get(0);
        assertThat(draft.request().modelName()).isEqualTo("ADR-MIN-001");
        assertThat(draft.request().spec().get("forceCont")).isEqualTo(80.0);
        // 命中少 → 置信度低，但不应阻断（Q5：仅提醒不阻断）
        assertThat(draft.confidence()).isLessThan(0.6);
    }
}
