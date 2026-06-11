package com.lobsterai.skillgateway.service;

import com.lobsterai.skillgateway.dto.ColumnMetadata;
import com.lobsterai.skillgateway.dto.ExcelMetadata;
import com.lobsterai.skillgateway.dto.RowData;
import com.lobsterai.skillgateway.dto.SheetMetadata;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ExcelParserService 单元测试
 */
class ExcelParserServiceTest {

    private final ExcelParserService service = new ExcelParserService();

    @Test
    void testExtractMetadata() throws IOException {
        // 创建测试用的 Excel 文件
        byte[] excelBytes = createTestExcel();

        // 调用元数据提取方法
        ExcelMetadata metadata = service.extractMetadata(excelBytes, "test.xlsx");

        // 验证基本信息
        assertEquals("excel", metadata.getFile_type());
        assertEquals(1, metadata.getSheet_count());

        // 验证 Sheet 信息
        List<SheetMetadata> sheets = metadata.getSheets();
        assertFalse(sheets.isEmpty());

        SheetMetadata sheet = sheets.get(0);
        assertEquals("账户余额表", sheet.getSheet_name());
        assertEquals(0, sheet.getSheet_index());
        assertEquals(6, sheet.getRow_count()); // 1 表头 + 5 数据行
        assertEquals(5, sheet.getCol_count());
        assertTrue(sheet.isHas_header());
        assertEquals(0, sheet.getHeader_row_index());

        // 验证列信息
        List<ColumnMetadata> columns = sheet.getColumns();
        assertEquals(5, columns.size());

        // 验证第一列（账户ID）
        ColumnMetadata col0 = columns.get(0);
        assertEquals(0, col0.getCol_index());
        assertEquals("A", col0.getCol_letter());
        assertEquals("账户ID", col0.getHeader_name());
        assertEquals("string", col0.getInferred_type());

        // 验证第三列（余额）
        ColumnMetadata col2 = columns.get(2);
        assertEquals(2, col2.getCol_index());
        assertEquals("C", col2.getCol_letter());
        assertEquals("余额", col2.getHeader_name());
        assertEquals("float", col2.getInferred_type());

        // 验证第四列（日期）
        ColumnMetadata col3 = columns.get(3);
        assertEquals(3, col3.getCol_index());
        assertEquals("D", col3.getCol_letter());
        assertEquals("最后交易日期", col3.getHeader_name());
        assertEquals("datetime", col3.getInferred_type());

        // 验证第五列（状态）
        ColumnMetadata col4 = columns.get(4);
        assertEquals(4, col4.getCol_index());
        assertEquals("E", col4.getCol_letter());
        assertEquals("状态", col4.getHeader_name());
        assertEquals("string", col4.getInferred_type());

        // 验证行数据（最多 10 行）
        List<RowData> rows = sheet.getRows();
        assertEquals(5, rows.size());

        // 验证第一行数据
        RowData row1 = rows.get(0);
        assertEquals(1, row1.getRow_index());
        List<Object> values1 = row1.getValues();
        assertEquals("ACC001", values1.get(0));
        assertEquals("张三储蓄账户", values1.get(1));
        assertEquals(125000.0, values1.get(2));
        assertEquals("2026-06-01", values1.get(3));
        assertEquals("正常", values1.get(4));

        System.out.println("✅ Excel 元数据提取测试通过！");
        System.out.println("📊 解析结果: " + metadata.getSheet_count() + " 个 Sheet");
        System.out.println("📋 Sheet: " + sheet.getSheet_name() + " (" + sheet.getRow_count() + "行 × " + sheet.getCol_count() + "列)");
    }

    private byte[] createTestExcel() throws IOException {
        Workbook workbook = new XSSFWorkbook();
        Sheet sheet = workbook.createSheet("账户余额表");

        // 创建表头样式
        CellStyle headerStyle = workbook.createCellStyle();
        Font headerFont = workbook.createFont();
        headerFont.setBold(true);
        headerStyle.setFont(headerFont);

        // 创建表头行
        Row headerRow = sheet.createRow(0);
        String[] headers = {"账户ID", "账户名称", "余额", "最后交易日期", "状态"};
        for (int i = 0; i < headers.length; i++) {
            Cell cell = headerRow.createCell(i);
            cell.setCellValue(headers[i]);
            cell.setCellStyle(headerStyle);
        }

        // 创建数据行
        createDataRow(sheet, 1, "ACC001", "张三储蓄账户", 125000.00, new Date(), "正常");
        createDataRow(sheet, 2, "ACC002", "李四结算账户", 89500.50, new Date(), "正常");
        createDataRow(sheet, 3, "ACC003", "王五活期账户", -3500.00, new Date(), "正常");
        createDataRow(sheet, 4, "ACC004", "赵六定期账户", 500000.00, new Date(), "冻结");
        createDataRow(sheet, 5, "ACC005", "孙七理财账户", 1280000.00, new Date(), "正常");

        // 写入字节数组
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        workbook.write(outputStream);
        workbook.close();

        return outputStream.toByteArray();
    }

    private void createDataRow(Sheet sheet, int rowNum, String accountId, String accountName,
                               double balance, Date date, String status) {
        Row row = sheet.createRow(rowNum);
        row.createCell(0).setCellValue(accountId);
        row.createCell(1).setCellValue(accountName);
        row.createCell(2).setCellValue(balance);

        // 创建日期单元格
        Cell dateCell = row.createCell(3);
        dateCell.setCellValue(date);
        CellStyle dateStyle = sheet.getWorkbook().createCellStyle();
        CreationHelper createHelper = sheet.getWorkbook().getCreationHelper();
        dateStyle.setDataFormat(createHelper.createDataFormat().getFormat("yyyy-MM-dd"));
        dateCell.setCellStyle(dateStyle);

        row.createCell(4).setCellValue(status);
    }

    @Test
    void testEmptyFile() {
        assertThrows(Exception.class, () -> {
            service.extractMetadata(new byte[0], "test.xlsx");
        });
    }

    @Test
    void testUnsupportedFormat() {
        assertThrows(Exception.class, () -> {
            service.extractMetadata(new byte[]{1, 2, 3}, "test.txt");
        });
    }
}