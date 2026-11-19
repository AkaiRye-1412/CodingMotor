package com.motorsizer.app.importing.ocr;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 本地离线 OCR（PP-OCRv6 small + ONNX Runtime），用于「参数表是位图」的页。
 *
 * <p>为什么不用 Tesseract：实测同一张 300dpi 表格，Tesseract 被表格线干扰，
 * 输出成 {@code Dox BusVolage} / {@code THE INEORMANON}；PP-OCR 能把 111 行逐行读对，
 * 坐标也足够还原四列布局。体积代价是模型 30MB + onnxruntime 原生库（打包时只保留 win-x64）。
 *
 * <p>流程与 PaddleOCR 一致：检测（DB）出文本框 → 逐框裁剪 → 识别（CRNN + CTC）。
 * 检测不实现旋转框，只取外接矩形——表格文字都是水平的。
 */
@Component
public class OcrEngine {

    private static final Logger log = LoggerFactory.getLogger(OcrEngine.class);

    private static final String DET_MODEL = "/ocr/PP-OCRv6_det_small.onnx";
    private static final String REC_MODEL = "/ocr/PP-OCRv6_rec_small.onnx";
    /**
     * 识别字典（每行一个字符，UTF-8）。
     *
     * <p>不用模型元数据 {@code character}：ONNX Runtime Java 的 {@code getCustomMetadata()}
     * 按平台默认编码解码，在中文 Windows（GBK）下会把汉字与 emoji 解成 {@code ?}，
     * 换行数也从 18707 掉到 18165，索引整体错位。这里改用 Python 侧导出的 UTF-8 文本，
     * 内容与模型训练时完全一致。
     */
    private static final String CHARSET_FILE = "/ocr/charset.txt";

    /**
     * 检测输入的最长边。
     *
     * <p>不能取 PaddleOCR 默认的 960/1600：整页 A4 按 300dpi 渲染是 2480×3508，
     * 缩到 1600 长边后表格里 8pt 的字只剩 13px，检测框会碎成单字、位置也偏。
     * 2560 时约 20px，检测稳定，单页耗时约 1s。
     */
    private static final int DET_MAX_SIDE = 2560;
    /** 识别输入高度（PP-OCR 固定 48）。 */
    private static final int REC_HEIGHT = 48;
    /** 识别输入最大宽度：表格里的长句（如 Continuous Power Dissipation）不能被压丢。 */
    private static final int REC_MAX_WIDTH = 1200;
    /** 文本框外扩像素（相对于检测图），补回 DB 收缩掉的边缘。 */
    private static final int BOX_PAD = 2;

    private final Object lock = new Object();
    private volatile boolean initialised;
    private volatile boolean available;
    private OrtSession detSession;
    private OrtSession recSession;
    private String[] charset;

    /** 模型是否可用（缺模型 / 缺原生库时为 false，调用方退化为「不做 OCR」）。 */
    public boolean available() {
        ensureLoaded();
        return available;
    }

    /**
     * 识别整页图像。
     *
     * @param page       页面渲染图（RGB）
     * @param minHeightPx 小于该高度的框直接丢弃（过滤边框线上的碎片）
     * @return 按 y 再按 x 排序的文字行；模型不可用时返回空列表
     */
    public List<OcrTextLine> recognize(BufferedImage page, int minHeightPx) {
        ensureLoaded();
        if (!available) {
            return List.of();
        }
        synchronized (lock) {
            try {
                return doRecognize(page, minHeightPx);
            } catch (OrtException | RuntimeException ex) {
                log.warn("OCR 识别失败: {}", ex.getMessage());
                return List.of();
            }
        }
    }

    // ---------------------------------------------------------------- 主流程

    private List<OcrTextLine> doRecognize(BufferedImage page, int minHeightPx) throws OrtException {
        int srcW = page.getWidth();
        int srcH = page.getHeight();

        // 1. 缩放到检测尺寸（32 的倍数），并记住回映射比例
        double scale = Math.min(1.0, (double) DET_MAX_SIDE / Math.max(srcW, srcH));
        int detW = Math.max(32, (int) Math.round(srcW * scale / 32) * 32);
        int detH = Math.max(32, (int) Math.round(srcH * scale / 32) * 32);
        BufferedImage detImage = ImageOps.resize(page, detW, detH);

        float[] detInput = new float[3 * detW * detH];
        ImageOps.fillChw(detImage, detInput);

        List<DetPostProcessor.Box> boxes = runDetector(detInput, detW, detH);

        // 2. 逐框裁剪 + 识别
        double toSrcX = (double) srcW / detW;
        double toSrcY = (double) srcH / detH;
        List<OcrTextLine> lines = new ArrayList<>();
        for (DetPostProcessor.Box box : boxes) {
            int x0 = (int) Math.max(0, Math.floor((box.x0() - BOX_PAD) * toSrcX));
            int y0 = (int) Math.max(0, Math.floor((box.y0() - BOX_PAD) * toSrcY));
            int x1 = (int) Math.min(srcW, Math.ceil((box.x1() + BOX_PAD) * toSrcX));
            int y1 = (int) Math.min(srcH, Math.ceil((box.y1() + BOX_PAD) * toSrcY));
            int w = x1 - x0;
            int h = y1 - y0;
            if (w < 4 || h < minHeightPx) {
                continue;
            }
            BufferedImage crop = ImageOps.crop(page, x0, y0, w, h, 0);
            Recognised r = runRecogniser(crop);
            if (r.text().isEmpty()) {
                continue;
            }
            lines.add(new OcrTextLine(r.text(), x0, y0, x1, y1, r.score()));
        }
        lines.sort((a, b) -> a.centerY() != b.centerY()
                ? Integer.compare(a.centerY(), b.centerY())
                : Integer.compare(a.x0(), b.x0()));
        return lines;
    }

    private List<DetPostProcessor.Box> runDetector(float[] input, int w, int h) throws OrtException {
        try (OnnxTensor tensor = OnnxTensor.createTensor(OrtEnvironment.getEnvironment(),
                FloatBuffer.wrap(input), new long[]{1, 3, h, w});
             OrtSession.Result result = detSession.run(Map.of("x", tensor))) {
            float[][][][] prob = (float[][][][]) result.get(0).getValue();
            return DetPostProcessor.extract(prob[0][0], w, h);
        }
    }

    /** 单行识别结果。 */
    private record Recognised(String text, float score) {
    }

    private Recognised runRecogniser(BufferedImage crop) throws OrtException {
        int h = crop.getHeight();
        int w = crop.getWidth();
        // 等比缩放到高 48；过宽的按上限压缩（否则显存/内存与耗时都会爆）
        int newW = Math.max(8, (int) Math.round((double) w * REC_HEIGHT / h));
        if (newW > REC_MAX_WIDTH) {
            newW = REC_MAX_WIDTH;
        }
        BufferedImage scaled = ImageOps.resize(crop, newW, REC_HEIGHT);

        float[] input = new float[3 * REC_HEIGHT * newW];
        ImageOps.fillChw(scaled, input);

        try (OnnxTensor tensor = OnnxTensor.createTensor(OrtEnvironment.getEnvironment(),
                FloatBuffer.wrap(input), new long[]{1, 3, REC_HEIGHT, newW});
             OrtSession.Result result = recSession.run(Map.of("x", tensor))) {
            float[][][] logits = (float[][][]) result.get(0).getValue();
            return decode(logits[0]);
        }
    }

    /**
     * CTC 贪心解码：逐帧取最大概率，跳过 blank（索引 0）与连续重复。
     *
     * @param logits [T][C]
     */
    private Recognised decode(float[][] logits) {
        StringBuilder sb = new StringBuilder();
        float minScore = 1f;
        int prev = -1;
        for (float[] frame : logits) {
            int best = 0;
            for (int c = 1; c < frame.length; c++) {
                if (frame[c] > frame[best]) {
                    best = c;
                }
            }
            if (best != 0 && best != prev) {
                if (best < charset.length) {
                    sb.append(charset[best]);
                    minScore = Math.min(minScore, frame[best]);
                }
            }
            prev = best;
        }
        return new Recognised(sb.toString().strip(), minScore == 1f ? 0f : minScore);
    }

    // ---------------------------------------------------------------- 模型加载

    private void ensureLoaded() {
        if (initialised) {
            return;
        }
        synchronized (lock) {
            if (initialised) {
                return;
            }
            initialised = true;
            try {
                OrtEnvironment env = OrtEnvironment.getEnvironment();
                OrtSession.SessionOptions opts = new OrtSession.SessionOptions();
                opts.setIntraOpNumThreads(Math.max(1, Math.min(4,
                        Runtime.getRuntime().availableProcessors() / 2)));

                detSession = env.createSession(readResource(DET_MODEL), opts);
                recSession = env.createSession(readResource(REC_MODEL), opts);
                charset = readCharset();
                available = true;
                log.info("OCR 模型加载完成：字典 {} 类", charset.length);
            } catch (IOException | OrtException | RuntimeException | UnsatisfiedLinkError ex) {
                available = false;
                log.warn("OCR 不可用（{}），位图型参数表将继续只做识别提示", ex.toString());
            }
        }
    }

    /**
     * 组索引表：与模型输出通道一一对应（18710 = 1 blank + 18708 字典 + 1 空格）。
     *
     * <p>PaddleOCR 的约定是「下标 0 = blank，末位再补一个空格字符」，
     * 漏补或多补都会让整串结果错位一个字符。
     */
    private String[] readCharset() throws IOException {
        String content = new String(readResource(CHARSET_FILE), java.nio.charset.StandardCharsets.UTF_8);
        String[] base = content.split("\n", -1);
        String[] table = new String[base.length + 2];
        table[0] = "";                              // blank 占位，永不输出
        System.arraycopy(base, 0, table, 1, base.length);
        table[table.length - 1] = " ";              // 末尾空格字符
        return table;
    }

    private byte[] readResource(String path) throws IOException {
        try (InputStream in = OcrEngine.class.getResourceAsStream(path)) {
            if (in == null) {
                throw new IOException("缺少 OCR 模型：" + path);
            }
            return in.readAllBytes();
        }
    }

    /** 供测试与诊断：当前字典大小（未加载时为 0）。 */
    int charsetSize() {
        ensureLoaded();
        return charset == null ? 0 : charset.length;
    }

    static Map<String, String> emptyInputs() {
        return new HashMap<>();
    }
}
