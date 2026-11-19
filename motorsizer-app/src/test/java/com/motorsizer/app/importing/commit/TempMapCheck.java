package com.motorsizer.app.importing.commit;

import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.motorsizer.domain.catalog.MotorCatalogTemplates;
import com.motorsizer.domain.catalog.MotorField;
import com.motorsizer.domain.catalog.MotorType;
import com.motorsizer.domain.catalog.MotorTypeTemplate;

/** 临时：逐行打印字段解析结果，定位 Kthw 为什么没匹配上。跑完即删。 */
class TempMapCheck {

    @Test
    void dump() throws Exception {
        String path = System.getProperty("mapcheck.xlsx",
                System.getenv("APPDATA") + "/MotorSizer/imports/imp-20261119-220640-8842/result.xlsx");
        List<CatalogSummarySheet.SheetRow> rows = CatalogSummarySheet.read(Path.of(path));
        CatalogRowMapper mapper = new CatalogRowMapper();
        MotorTypeTemplate template = MotorCatalogTemplates.of(MotorType.LINEAR);

        Method resolve = CatalogRowMapper.class.getDeclaredMethod(
                "resolveField", MotorTypeTemplate.class, CatalogSummarySheet.SheetRow.class);
        resolve.setAccessible(true);
        Method norm = CatalogRowMapper.class.getDeclaredMethod("normName", String.class, boolean.class);
        norm.setAccessible(true);

        java.io.PrintWriter out = new java.io.PrintWriter(
                new java.io.OutputStreamWriter(new java.io.FileOutputStream(
                        "C:/Users/Administrator/Desktop/motor/CodingMotor/.workbuddy/tmp/mapcheck.txt"),
                        java.nio.charset.StandardCharsets.UTF_8));
        for (CatalogSummarySheet.SheetRow row : rows) {
            if (!row.model().startsWith("AKM200")) {
                continue;
            }
            MotorField field = (MotorField) resolve.invoke(mapper, template, row);
            out.println(row.name() + " | sym=" + row.symbol()
                    + " | normName=" + norm.invoke(null, row.name(), true)
                    + " -> " + (field == null ? "NULL" : field.key()));
            if (row.name().startsWith("Thermal") || row.name().startsWith("Magnetic")) {
                String nn = (String) norm.invoke(null, row.name(), true);
                for (MotorField f : template.fields()) {
                    java.util.List<String> names = new java.util.ArrayList<>(f.aliases());
                    names.add(f.label());
                    for (String c : names) {
                        String t = (String) norm.invoke(null, c, true);
                        if (t.length() >= 2 && (t.contains(nn) || nn.contains(t))) {
                            out.println("      MATCH " + f.key() + " <- [" + c + "] = " + t);
                        }
                    }
                }
            }
        }
        out.close();
        System.out.println("WROTE");
    }
}
