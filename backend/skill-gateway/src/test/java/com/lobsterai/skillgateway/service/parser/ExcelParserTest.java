package com.lobsterai.skillgateway.service.parser;

import com.lobsterai.skillgateway.dto.FileParseResult;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ExcelParser 单元测试（统一解析器模式）- Java 8 兼容
 */
public class ExcelParserTest {

    private final ExcelParser parser = new ExcelParser();

    @Test
    public void testParseXlsx() throws Exception {
        byte[] excelBytes = createTestExcel();
        
        FileParseResult result = parser.parse(excelBytes, "test.xlsx");
        
        assertEquals("excel", result.getFileType());
        assertEquals(1, result.getSheetCount());
        
        List<FileParseResult.SheetInfo> sheets = result.getSheets();
        assertFalse(sheets.isEmpty());
        
        FileParseResult.SheetInfo sheet = sheets.get(0);
        assertEquals("账户余额表", sheet.getSheetName());
        assertEquals(0, sheet.getSheetIndex());
        assertEquals(6, sheet.getRowCount());
        assertEquals(5, sheet.getColCount());
        assertTrue(sheet.getHasHeader());
        assertEquals(0, sheet.getHeaderRowIndex());
        assertFalse(sheet.getHasFormula());
        assertFalse(sheet.getHasChart());
        
        List<FileParseResult.ColumnInfo> columns = sheet.getColumns();
        assertEquals(5, columns.size());
        
        FileParseResult.ColumnInfo col0 = columns.get(0);
        assertEquals(0, col0.getColIndex());
        assertEquals("A", col0.getColLetter());
        assertEquals("账户ID", col0.getHeaderName());
        assertEquals("string", col0.getInferredType());
        
        FileParseResult.ColumnInfo col2 = columns.get(2);
        assertEquals(2, col2.getColIndex());
        assertEquals("C", col2.getColLetter());
        assertEquals("余额", col2.getHeaderName());
        assertEquals("float", col2.getInferredType());
        
        FileParseResult.ColumnInfo col3 = columns.get(3);
        assertEquals(3, col3.getColIndex());
        assertEquals("D", col3.getColLetter());
        assertEquals("最后交易日期", col3.getHeaderName());
        assertEquals("datetime", col3.getInferredType());
        
        FileParseResult.ColumnInfo col4 = columns.get(4);
        assertEquals(4, col4.getColIndex());
        assertEquals("E", col4.getColLetter());
        assertEquals("状态", col4.getHeaderName());
        assertEquals("string", col4.getInferredType());
        
        System.out.println("✅ Excel 解析器测试通过！");
        System.out.println("📊 解析结果: " + result.getSheetCount() + " 个 Sheet");
        System.out.println("📋 Sheet: " + sheet.getSheetName() + " (" + sheet.getRowCount() + "行 × " + sheet.getColCount() + "列)");
    }

    @Test
    public void testSupportedType() {
        assertEquals("xlsx", parser.supportedType());
    }

    private byte[] createTestExcel() throws IOException {
        Workbook workbook = new XSSFWorkbook();
        Sheet sheet = workbook.createSheet("账户余额表");
        
        Row headerRow = sheet.createRow(0);
        String[] headers = {"账户ID", "账户名称", "余额", "最后交易日期", "状态"};
        for (int i = 0; i < headers.length; i++) {
            Cell cell = headerRow.createCell(i);
            cell.setCellValue(headers[i]);
        }
        
        createDataRow(sheet, 1, "ACC001", "张三储蓄账户", 125000.00, new Date(), "正常");
        createDataRow(sheet, 2, "ACC002", "李四结算账户", 89500.50, new Date(), "正常");
        createDataRow(sheet, 3, "ACC003", "王五活期账户", -3500.00, new Date(), "正常");
        createDataRow(sheet, 4, "ACC004", "赵六定期账户", 500000.00, new Date(), "冻结");
        createDataRow(sheet, 5, "ACC005", "孙七理财账户", 1280000.00, new Date(), "正常");
        
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
        
        Cell dateCell = row.createCell(3);
        dateCell.setCellValue(date);
        CellStyle dateStyle = sheet.getWorkbook().createCellStyle();
        CreationHelper createHelper = sheet.getWorkbook().getCreationHelper();
        dateStyle.setDataFormat(createHelper.createDataFormat().getFormat("yyyy-MM-dd"));
        dateCell.setCellStyle(dateStyle);
        
        row.createCell(4).setCellValue(status);
    }
}