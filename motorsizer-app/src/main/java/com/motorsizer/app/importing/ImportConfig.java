package com.motorsizer.app.importing;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.file.Path;

/**
 * 样册识别运行参数（06 §2 / §3）。
 *
 * <p>默认基址 {@code %APPDATA%\MotorSizer\imports}，与库文件同处（A-5）。
 * 测试用 {@code motorsizer.imports.dir} 指向临时目录，并据此跳过启动期自动清理，避免误删真实数据。
 */
@Component
public class ImportConfig {

    private final Path baseDir;
    private final boolean overridden;
    private final int retentionDays;

    public ImportConfig(@Value("${motorsizer.imports.dir:}") String override,
                        @Value("${user.home}") String userHome,
                        @Value("${motorsizer.imports.retentionDays:30}") int retentionDays) {
        this.overridden = override != null && !override.isBlank();
        this.baseDir = this.overridden
                ? Path.of(override)
                : Path.of(userHome, "AppData", "Roaming", "MotorSizer", "imports");
        this.retentionDays = retentionDays;
    }

    public Path baseDir() {
        return baseDir;
    }

    public Path jobDir(String jobId) {
        return baseDir.resolve(jobId);
    }

    public Path jobFile(String jobId) {
        return jobDir(jobId).resolve("job.json");
    }

    public Path sourceDir(String jobId) {
        return jobDir(jobId).resolve("source");
    }

    public int retentionDays() {
        return retentionDays;
    }

    /** 是否被测试/显式覆盖——启动期自动清理据此跳过，避免在测试里误删真实 imports 目录。 */
    public boolean isOverridden() {
        return overridden;
    }
}
