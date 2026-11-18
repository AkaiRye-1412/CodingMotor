package com.motorsizer.app.importing;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 启动期惰性清理过期导入任务（06 §3 Q3：30 天）。
 *
 * <p>仅在真实运行路径（imports 目录未被测试覆盖）下执行，避免在 @SpringBootTest 中误删真实数据。
 * 真实环境下 ApplicationReadyEvent 触发一次扫描。
 */
@Component
public class ImportCleanup {

    private static final Logger log = LoggerFactory.getLogger(ImportCleanup.class);

    private final ImportService service;
    private final ImportConfig config;

    public ImportCleanup(ImportService service, ImportConfig config) {
        this.service = service;
        this.config = config;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        if (config.isOverridden()) {
            return;   // 测试临时目录，跳过
        }
        try {
            service.purgeExpired();
        } catch (Exception ex) {
            log.warn("启动期导入任务清理失败: {}", ex.getMessage());
        }
    }
}
