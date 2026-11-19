package com.motorsizer.app.importing.commit;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 读回「参数汇总」页（06 §7 固定 6 列：型号 / 类别 / 参数名 / 符号 / 单位 / 值）。
 *
 * <p>入库以<b>Excel 为唯一数据源</b>，而不是回头再用内存里的解析结果——用户拿到的、核对过的就是这份
 * xlsx（06 §7「采集用固定 6 列」），两者若不一致，以用户手上那份为准。
 *
 * <p>值列可能是数字格（按原小数位设了格式）也可能是文本格（超长/前导零），一律用
 * {@link DataFormatter} 取「看到的那串字符」，保证 {@code 3.4} 不会变成 {@code 3.4000000000000004}。
 */
public final class CatalogSummarySheet {

    /** 一行原始记录（全字符串，解析与换算交给 {@link CatalogRowMapper}）。 */
    public record SheetRow(String model, String section, String name, String symbol, String unit, String value) {
    }

    private static final String SUMMARY_SHEET = "参数汇总";
    private static final int COL_MODEL = 0;
    private static final int COL_SECTION = 1;
    private static final int COL_NAME = 2;
    private static final int COL_SYMBOL = 3;
    private static final int COL_UNIT = 4;
    private static final int COL_VALUE = 5;

    private CatalogSummarySheet() {
    }

    public static List<SheetRow> read(Path xlsx) throws IOException {
        try (InputStream in = Files.newInputStream(xlsx);
             Workbook wb = WorkbookFactory.create(in)) {
            Sheet sheet = wb.getSheet(SUMMARY_SHEET);
            if (sheet == null) {
                sheet = wb.getSheetAt(0);
            }
            DataFormatter formatter = new DataFormatter();
            int last = sheet.getLastRowNum();
            List<SheetRow> rows = new ArrayList<>();
            // 第 0 行是深蓝表头，从第 1 行起读
            for (int r = 1; r <= last; r++) {
                Row row = sheet.getRow(r);
                if (row == null) {
                    continue;
                }
                String model = text(formatter, row, COL_MODEL);
                if (model.isEmpty()) {
                    continue;                       // 空行/脚注残留：没有型号就没有可入库的主体
                }
                rows.add(new SheetRow(
                        model,
                        text(formatter, row, COL_SECTION),
                        text(formatter, row, COL_NAME),
                        text(formatter, row, COL_SYMBOL),
                        text(formatter, row, COL_UNIT),
                        text(formatter, row, COL_VALUE)));
            }
            return rows;
        }
    }

    private static String text(DataFormatter formatter, Row row, int column) {
        Cell cell = row.getCell(column);
        if (cell == null) {
            return "";
        }
        return formatter.formatCellValue(cell).strip();
    }
}
