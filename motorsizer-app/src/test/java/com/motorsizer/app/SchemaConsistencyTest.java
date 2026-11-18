package com.motorsizer.app;

import com.motorsizer.domain.catalog.MotorCatalogTemplates;
import com.motorsizer.domain.catalog.MotorField;
import com.motorsizer.domain.catalog.MotorType;
import com.motorsizer.domain.catalog.MotorTypeTemplate;
import org.h2.tools.RunScript;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * schema.sql ↔ K2 模板一致性（03 §6.4 / 风险 R-13）。
 *
 * <p>启动内存 H2 执行真实建表脚本，逐列比对：
 * 列集合 == 模板 key 集合；NOT NULL 列 == 模板必填集合。
 * 同时校验规则 R4-2：脚本不得出现物理外键。
 */
class SchemaConsistencyTest {

    private static final Map<MotorType, String> SPEC_TABLES = Map.of(
            MotorType.LINEAR, "MOTOR_LINEAR_SPEC",
            MotorType.TORQUE, "MOTOR_TORQUE_SPEC",
            MotorType.COIL, "MOTOR_COIL_SPEC");

    /** 规格表中不属于业务字段的列。 */
    private static final Set<String> NON_BUSINESS_COLUMNS = Set.of(
            "ID", "MOTOR_ID", "CREATED_AT", "UPDATED_AT");

    private static Connection connection;
    private static String schemaSql;

    @BeforeAll
    static void setUp() throws Exception {
        schemaSql = readSchema();
        connection = DriverManager.getConnection(
                "jdbc:h2:mem:schema_consistency;MODE=MySQL;DB_CLOSE_DELAY=-1");
        RunScript.execute(connection, new java.io.StringReader(schemaSql));
    }

    @AfterAll
    static void tearDown() throws Exception {
        if (connection != null) {
            connection.close();
        }
    }

    @Test
    void schemaHasNoPhysicalForeignKeys() {
        String normalized = schemaSql.toUpperCase().replaceAll("\\s+", " ");
        assertThat(normalized).doesNotContain("FOREIGN KEY");
        assertThat(normalized).doesNotContain("REFERENCES");
    }

    @Test
    void everyTemplateFieldMapsToADatabaseColumn() throws Exception {
        for (MotorType type : MotorType.values()) {
            Set<String> columns = businessColumns(SPEC_TABLES.get(type));
            Set<String> templateKeys = new LinkedHashSet<>();
            MotorCatalogTemplates.of(type).fields().forEach(field -> templateKeys.add(field.key()));

            assertThat(columns)
                    .as("%s 规格表列 vs 模板字段", type)
                    .containsExactlyInAnyOrderElementsOf(templateKeys);
        }
    }

    @Test
    void notNullColumnsEqualTemplateRequiredSet() throws Exception {
        for (MotorType type : MotorType.values()) {
            Set<String> notNullColumns = notNullBusinessColumns(SPEC_TABLES.get(type));
            MotorTypeTemplate template = MotorCatalogTemplates.of(type);
            Set<String> requiredKeys = new HashSet<>(template.requiredKeys());

            assertThat(notNullColumns)
                    .as("%s 的 NOT NULL 列必须等于 K2 req 集合", type)
                    .containsExactlyInAnyOrderElementsOf(requiredKeys);
        }
    }

    private static Set<String> businessColumns(String table) throws Exception {
        Set<String> columns = new HashSet<>();
        for (String column : allColumns(table)) {
            if (!NON_BUSINESS_COLUMNS.contains(column)) {
                columns.add(toCamelCase(column));
            }
        }
        return columns;
    }

    private static Set<String> notNullBusinessColumns(String table) throws Exception {
        Set<String> columns = new HashSet<>();
        DatabaseMetaData meta = connection.getMetaData();
        try (ResultSet rs = meta.getColumns(connection.getCatalog(), null, table, null)) {
            while (rs.next()) {
                String column = rs.getString("COLUMN_NAME");
                if (NON_BUSINESS_COLUMNS.contains(column)) {
                    continue;
                }
                if (rs.getInt("NULLABLE") == DatabaseMetaData.columnNoNulls) {
                    columns.add(toCamelCase(column));
                }
            }
        }
        return columns;
    }

    private static Set<String> allColumns(String table) throws Exception {
        Set<String> columns = new HashSet<>();
        DatabaseMetaData meta = connection.getMetaData();
        try (ResultSet rs = meta.getColumns(connection.getCatalog(), null, table, null)) {
            while (rs.next()) {
                columns.add(rs.getString("COLUMN_NAME"));
            }
        }
        return columns;
    }

    /** snake_case → camelCase（与接口字段命名约定一致）。 */
    private static String toCamelCase(String snake) {
        StringBuilder sb = new StringBuilder();
        boolean upperNext = false;
        for (char c : snake.toLowerCase().toCharArray()) {
            if (c == '_') {
                upperNext = true;
                continue;
            }
            sb.append(upperNext ? Character.toUpperCase(c) : c);
            upperNext = false;
        }
        return sb.toString();
    }

    private static String readSchema() throws Exception {
        try (InputStream in = SchemaConsistencyTest.class.getResourceAsStream("/db/schema.sql")) {
            assertThat(in).as("classpath:/db/schema.sql 必须存在").isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
