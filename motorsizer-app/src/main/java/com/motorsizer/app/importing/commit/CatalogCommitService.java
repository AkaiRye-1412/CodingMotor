package com.motorsizer.app.importing.commit;

import com.motorsizer.app.common.BusinessException;
import com.motorsizer.app.common.ErrorCode;
import com.motorsizer.app.dto.MotorUpsertRequest;
import com.motorsizer.app.importing.ImportConfig;
import com.motorsizer.app.importing.ImportJob;
import com.motorsizer.app.importing.ImportStore;
import com.motorsizer.app.service.MotorService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 样册「入库」（06 v2 §6.1）：把已生成的 Excel 里的电机写进电机库。
 *
 * <p>口径：
 * <ul>
 *   <li><b>数据源是 Excel</b>，不是内存里的解析结果——用户核对的就是这份表；</li>
 *   <li><b>主键相同即覆盖</b>（型号名 + 配置名），走 {@link MotorService#update} 的整体替换语义，
 *       不做字段级合并；不存在则 {@link MotorService#create} 新增；</li>
 *   <li><b>逐台独立事务</b>：一台失败不影响其他台，结果逐台回报（v1 Q4 口径沿用）；</li>
 *   <li><b>必填不全就跳过</b>，不补 0、不猜值——写进库的每台电机都必须通过 C3 校验。</li>
 * </ul>
 *
 * <p>结构与冷却在样册里通常没有（图纸只给性能参数），按 K2 模板首项兜底
 * （直线=无铁芯/普通、力矩=有铁芯/普通、音圈=音圈/普通），并在结果里逐台回显，用户可在电机库中改。
 */
@Service
public class CatalogCommitService {

    private static final Logger log = LoggerFactory.getLogger(CatalogCommitService.class);
    private static final String ENTRY_METHOD = "识别草稿";
    private static final int SOURCE_DOC_MAX = 200;
    private static final int NOTE_MAX = 1000;

    private final ImportStore store;
    private final ImportConfig config;
    private final CatalogRowMapper mapper;
    private final MotorService motorService;

    public CatalogCommitService(ImportStore store, ImportConfig config,
                                CatalogRowMapper mapper, MotorService motorService) {
        this.store = store;
        this.config = config;
        this.mapper = mapper;
        this.motorService = motorService;
    }

    public ImportCommitResult commit(String jobId) throws IOException {
        Optional<ImportJob> stored = store.read(jobId);
        if (stored.isEmpty()) {
            throw new BusinessException(ErrorCode.IMPORT_JOB_NOT_FOUND,
                    "转换任务不存在或已清理: " + jobId);
        }
        ImportJob job = stored.get();
        Path xlsx = config.resultFile(jobId);
        if (!Files.exists(xlsx)) {
            throw new BusinessException(ErrorCode.IMPORT_JOB_NOT_FOUND,
                    "转换结果已清理，请重新上传样册");
        }

        List<CatalogSummarySheet.SheetRow> rows;
        try {
            rows = CatalogSummarySheet.read(xlsx);
        } catch (IOException | RuntimeException ex) {
            log.warn("读取 Excel 失败: {} ({})", jobId, ex.getMessage(), ex);
            throw new BusinessException(ErrorCode.IMPORT_COMMIT_FAILED,
                    "读取 Excel 失败：" + ex.getMessage());
        }

        List<CatalogRowMapper.MappedMotor> motors = mapper.map(rows);
        if (motors.isEmpty()) {
            throw new BusinessException(ErrorCode.IMPORT_NOTHING_TO_COMMIT,
                    "Excel 里没有可入库的电机：请先确认「参数汇总」页有数据行");
        }

        String sourceDoc = sourceDocOf(job);
        List<ImportCommitItem> items = new ArrayList<>(motors.size());
        for (CatalogRowMapper.MappedMotor motor : motors) {
            items.add(commitOne(jobId, sourceDoc, motor));
        }

        ImportCommitResult result = ImportCommitResult.of(jobId, sourceDoc, items);
        log.info("样册入库完成: {}（{}）", jobId, result.summary());
        return result;
    }

    private ImportCommitItem commitOne(String jobId, String sourceDoc,
                                       CatalogRowMapper.MappedMotor motor) {
        String type = motor.type().code();
        if (!motor.missing().isEmpty()) {
            return ImportCommitItem.skipped(motor.modelName(), motor.configName(), type,
                    motor.structure(), motor.cooling(),
                    "缺少必填参数：" + String.join("、", motor.missing()));
        }

        MotorUpsertRequest request = new MotorUpsertRequest(
                motor.modelName(),
                motor.configName(),
                type,
                motor.structure(),
                motor.cooling(),
                null,                                   // 中孔：样册不提供
                null,                                   // 厂商：样册内无结构化字段
                null,                                   // 系列
                sourceDoc,
                null,                                   // 页码：Excel 未保留（报告页另有未识别页清单）
                ENTRY_METHOD,
                LocalDate.now(),
                note(jobId, motor),
                new java.util.LinkedHashMap<>(motor.spec()));

        try {
            Long existing = motorService.findId(motor.modelName(), motor.configName());
            if (existing != null) {
                motorService.update(existing, request);
                return ImportCommitItem.updated(motor.modelName(), motor.configName(), type,
                        motor.structure(), motor.cooling(), existing);
            }
            Long created = motorService.create(request);
            return ImportCommitItem.created(motor.modelName(), motor.configName(), type,
                    motor.structure(), motor.cooling(), created);
        } catch (BusinessException ex) {
            return ImportCommitItem.failed(motor.modelName(), motor.configName(), type,
                    motor.structure(), motor.cooling(), describe(ex));
        } catch (RuntimeException ex) {
            log.warn("入库单台失败: {} {} / {} ({})", jobId, motor.modelName(),
                    motor.configName(), ex.getMessage(), ex);
            return ImportCommitItem.failed(motor.modelName(), motor.configName(), type,
                    motor.structure(), motor.cooling(), "保存失败，请重试");
        }
    }

    private String describe(BusinessException ex) {
        String base = ex.getMessage() == null ? ex.errorCode().defaultMessage() : ex.getMessage();
        if (ex.errors().isEmpty()) {
            return base;
        }
        String detail = ex.errors().stream()
                .map(error -> error.reason())
                .distinct()
                .limit(4)
                .reduce((a, b) -> a + "；" + b)
                .orElse("");
        return detail.isEmpty() ? base : base + "（" + detail + "）";
    }

    private String note(String jobId, CatalogRowMapper.MappedMotor motor) {
        String note = "样册识别入库（任务 " + jobId + "）";
        if (!motor.notes().isEmpty()) {
            note = note + "：" + String.join("；", motor.notes());
        }
        return note.length() > NOTE_MAX ? note.substring(0, NOTE_MAX) : note;
    }

    private String sourceDocOf(ImportJob job) {
        String name = job.files().isEmpty() ? null : job.files().get(0).name();
        if (name == null || name.isBlank()) {
            name = job.stats() == null ? "样册" : job.stats().excelName();
        }
        if (name == null || name.isBlank()) {
            name = "样册";
        }
        return name.length() > SOURCE_DOC_MAX ? name.substring(0, SOURCE_DOC_MAX) : name;
    }
}
