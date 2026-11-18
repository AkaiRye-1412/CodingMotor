package com.motorsizer.app.controller;

import com.motorsizer.app.common.ApiResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.sql.DataSource;
import java.sql.Connection;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * E1：健康检查（04 §3 E1）。供启动探测与前端启动检查使用，不参与业务。
 *
 * <p>数据库探测只取连接有效性，不执行任何业务 SQL，也不返回连接串等内部信息。
 */
@RestController
@RequestMapping("/api")
public class HealthController {

    private static final Logger log = LoggerFactory.getLogger(HealthController.class);

    private final DataSource dataSource;
    private final String version;

    public HealthController(DataSource dataSource,
                            @Value("${motorsizer.version:1.0.0}") String version) {
        this.dataSource = dataSource;
        this.version = version;
    }

    @GetMapping("/health")
    public ApiResponse<Map<String, Object>> health() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("status", "UP");
        data.put("database", databaseUp() ? "UP" : "DOWN");
        data.put("version", version);
        return ApiResponse.ok(data);
    }

    private boolean databaseUp() {
        try (Connection connection = dataSource.getConnection()) {
            return connection.isValid(1);
        } catch (Exception ex) {
            // 探测失败只记日志，响应中不暴露异常细节（Java 规范 12）
            log.warn("健康检查：数据库连接不可用", ex);
            return false;
        }
    }
}
