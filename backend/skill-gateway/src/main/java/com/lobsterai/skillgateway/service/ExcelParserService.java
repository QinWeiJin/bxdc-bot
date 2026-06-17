package com.lobsterai.skillgateway.service;

import com.lobsterai.skillgateway.exception.ExcelParseException;
import com.lobsterai.skillgateway.dto.*;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Excel 表格解析服务。
 *
 * 支持 .xlsx（OOXML）和 .xls（旧二进制格式）。
 * 提供纯文本提取和元数据提取两种功能。
 */
@Service
public class ExcelParserService {

    private static final int TEXT_MAX_BYTES = 80 * 1024;
    private static final int MAX_PREVIEW_ROWS = 10;

    public ExcelParseResult parse(byte[] fileBytes, String fileName) {
        if (fileBytes == null || fileBytes.length == 0) {
            throw new ExcelParseException("EXCEL_PARSE_ERROR", "文件为空");
        }

        String lower = fileName == null ? "" : fileName.toLowerCase();
        try {
            if (lower.endsWith(".xlsx")) {
                return parseXlsx(fileBytes);
            } else if (lower.endsWith(".xls")) {
                return parseXls(fileBytes);
            } else {
                throw new ExcelParseException("EXCEL_UNSUPPORTED_TYPE",
                        "不支持的 Excel 格式：" + fileName);
            }
        } catch (ExcelParseException e) {
            throw e;
        } catch (IOException e) {
            throw new ExcelParseException("EXCEL_PARSE_ERROR", "Excel 文件读取失败：" + e.getMessage(), e);
        } catch (RuntimeException e) {
            String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            throw new ExcelParseException("EXCEL_PARSE_ERROR", "Excel 文件解析失败：" + msg, e);
        }
    }

    public ExcelMetadata extractMetadata(byte[] fileBytes, String fileName) {
        if (fileBytes == null || fileBytes.length == 0) {
            throw new ExcelParseException("EXCEL_PARSE_ERROR", "文件为空");
        }

        String lower = fileName == null ? "" : fileName.toLowerCase();
        try {
            if (lower.endsWith(".xlsx")) {
                return extractMetadataXlsx(fileBytes);
            } else if (lower.endsWith(".xls")) {
                return extractMetadataXls(fileBytes);
            } else {
                throw new ExcelParseException("EXCEL_UNSUPPORTED_TYPE",
                        "不支持的 Excel 格式：" + fileName);
            }
        } catch (ExcelParseException e) {
            throw e;
        } catch (IOException e) {
            throw new ExcelParseException("EXCEL_PARSE_ERROR", "Excel 文件读取失败：" + e.getMessage(), e);
        } catch (RuntimeException e) {
            String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            throw new ExcelParseException("EXCEL_PARSE_ERROR", "Excel 文件解析失败：" + msg, e);
        }
    }

    private ExcelMetadata extractMetadataXlsx(byte[] fileBytes) throws IOException {
        ByteArrayInputStream bais = new ByteArrayInputStream(fileBytes);
        try (XSSFWorkbook wb = new XSSFWorkbook(bais)) {
            return extractMetadataFromWorkbook(wb);
        }
    }

    private ExcelMetadata extractMetadataXls(byte[] fileBytes) throws IOException {
        ByteArrayInputStream bais = new ByteArrayInputStream(fileBytes);
        try (HSSFWorkbook wb = new HSSFWorkbook(bais)) {
            return extractMetadataFromWorkbook(wb);
        }
    }

    private ExcelMetadata extractMetadataFromWorkbook(Workbook wb) {
        ExcelMetadata metadata = new ExcelMetadata();
        int sheetCount = wb.getNumberOfSheets();
        metadata.setSheet_count(sheetCount);

        for (int i = 0; i < sheetCount; i++) {
            Sheet sheet = wb.getSheetAt(i);
            SheetMetadata sheetMeta = extractSheetMetadata(sheet, i);
            metadata.addSheet(sheetMeta);
        }

        return metadata;
    }

    private SheetMetadata extractSheetMetadata(Sheet sheet, int sheetIndex) {
        SheetMetadata sheetMeta = new SheetMetadata();
        sheetMeta.setSheet_name(sheet.getSheetName());
        sheetMeta.setSheet_index(sheetIndex);

        int rowCount = 0;
        int maxColCount = 0;
        List<Row> allRows = new ArrayList<>();

        for (Row row : sheet) {
            allRows.add(row);
            rowCount++;
            int lastCol = row.getLastCellNum();
            if (lastCol > maxColCount) {
                maxColCount = lastCol;
            }
        }

        sheetMeta.setRow_count(rowCount);
        sheetMeta.setCol_count(maxColCount);

        if (rowCount > 0 && maxColCount > 0) {
            List<ColumnMetadata> columns = extractColumnMetadata(sheet, allRows, maxColCount);
            sheetMeta.setColumns(columns);

            List<RowData> rows = extractRowData(sheet, allRows, columns, maxColCount);
            sheetMeta.setRows(rows);
        }

        return sheetMeta;
    }

    private List<ColumnMetadata> extractColumnMetadata(Sheet sheet, List<Row> allRows, int colCount) {
        List<ColumnMetadata> columns = new ArrayList<>();
        Row headerRow = allRows.isEmpty() ? null : allRows.get(0);

        for (int colIndex = 0; colIndex < colCount; colIndex++) {
            ColumnMetadata column = new ColumnMetadata();
            column.setCol_index(colIndex);

            if (headerRow != null) {
                Cell headerCell = headerRow.getCell(colIndex, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
                column.setHeader_name(headerCell != null ? getCellStringValue(headerCell) : "Column" + (colIndex + 1));
            } else {
                column.setHeader_name("Column" + (colIndex + 1));
            }

            String inferredType = inferColumnType(sheet, colIndex, allRows);
            column.setInferred_type(inferredType);

            columns.add(column);
        }

        return columns;
    }

    private String inferColumnType(Sheet sheet, int colIndex, List<Row> allRows) {
        int dateCount = 0;
        int numberCount = 0;
        int stringCount = 0;
        int totalCount = 0;

        DataFormatter formatter = new DataFormatter();

        for (int i = 1; i < Math.min(allRows.size(), 50); i++) {
            Row row = allRows.get(i);
            Cell cell = row.getCell(colIndex, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
            if (cell == null) continue;

            totalCount++;

            switch (cell.getCellType()) {
                case NUMERIC:
                    if (DateUtil.isCellDateFormatted(cell)) {
                        dateCount++;
                    } else {
                        numberCount++;
                    }
                    break;
                case STRING:
                    stringCount++;
                    break;
                case BOOLEAN:
                    numberCount++;
                    break;
                default:
                    stringCount++;
            }
        }

        if (totalCount == 0) {
            return "string";
        }

        double dateRatio = (double) dateCount / totalCount;
        double numberRatio = (double) numberCount / totalCount;

        if (dateRatio > 0.5) {
            return "datetime";
        } else if (numberRatio > 0.7) {
            return "float";
        } else {
            return "string";
        }
    }

    private List<RowData> extractRowData(Sheet sheet, List<Row> allRows, List<ColumnMetadata> columns, int colCount) {
        List<RowData> rows = new ArrayList<>();
        int startRow = 1;
        int endRow = Math.min(startRow + MAX_PREVIEW_ROWS, allRows.size());

        for (int i = startRow; i < endRow; i++) {
            Row row = allRows.get(i);
            RowData rowData = new RowData();
            rowData.setRow_index(i);

            for (int colIndex = 0; colIndex < colCount; colIndex++) {
                Cell cell = row.getCell(colIndex, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
                ColumnMetadata column = colIndex < columns.size() ? columns.get(colIndex) : null;

                Object value = getTypedCellValue(cell, column);
                rowData.addValue(value);
            }

            rows.add(rowData);
        }

        return rows;
    }

    private Object getTypedCellValue(Cell cell, ColumnMetadata column) {
        if (cell == null) {
            return null;
        }

        String inferredType = column != null ? column.getInferred_type() : null;

        switch (cell.getCellType()) {
            case NUMERIC:
                if (DateUtil.isCellDateFormatted(cell)) {
                    return cell.getLocalDateTimeCellValue().toLocalDate().toString();
                } else {
                    double value = cell.getNumericCellValue();
                    if (value == Math.floor(value)) {
                        return (long) value;
                    }
                    return value;
                }
            case STRING:
                return cell.getStringCellValue();
            case BOOLEAN:
                return cell.getBooleanCellValue();
            case FORMULA:
                try {
                    if (inferredType != null && inferredType.equals("datetime")) {
                        return cell.getLocalDateTimeCellValue().toLocalDate().toString();
                    }
                    double formulaValue = cell.getNumericCellValue();
                    if (formulaValue == Math.floor(formulaValue)) {
                        return (long) formulaValue;
                    }
                    return formulaValue;
                } catch (Exception e) {
                    return new DataFormatter().formatCellValue(cell);
                }
            default:
                return null;
        }
    }

    private String getCellStringValue(Cell cell) {
        if (cell == null) {
            return "";
        }
        DataFormatter formatter = new DataFormatter();
        return formatter.formatCellValue(cell).trim();
    }

    private ExcelParseResult parseXlsx(byte[] fileBytes) throws IOException {
        ByteArrayInputStream bais = new ByteArrayInputStream(fileBytes);
        try (XSSFWorkbook wb = new XSSFWorkbook(bais)) {
            return extractText(wb);
        }
    }

    private ExcelParseResult parseXls(byte[] fileBytes) throws IOException {
        ByteArrayInputStream bais = new ByteArrayInputStream(fileBytes);
        try (HSSFWorkbook wb = new HSSFWorkbook(bais)) {
            return extractText(wb);
        }
    }

    private ExcelParseResult extractText(Workbook wb) {
        StringBuilder sb = new StringBuilder();
        int sheetCount = wb.getNumberOfSheets();

        for (int i = 0; i < sheetCount; i++) {
            Sheet sheet = wb.getSheetAt(i);
            sb.append("--- Sheet: ").append(sheet.getSheetName()).append(" ---\n");

            DataFormatter formatter = new DataFormatter();
            for (Row row : sheet) {
                int lastCol = row.getLastCellNum();
                if (lastCol < 0) continue;
                for (int c = 0; c < lastCol; c++) {
                    if (c > 0) sb.append("\t");
                    Cell cell = row.getCell(c, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
                    if (cell != null) {
                        sb.append(formatter.formatCellValue(cell));
                    }
                }
                sb.append("\n");
            }
            sb.append("\n");
        }

        return truncate(sb.toString(), sheetCount);
    }

    private ExcelParseResult truncate(String text, int sheetCount) {
        if (text == null) {
            return new ExcelParseResult("", sheetCount);
        }
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        if (bytes.length <= TEXT_MAX_BYTES) {
            return new ExcelParseResult(text, sheetCount);
        }
        int maxChars = TEXT_MAX_BYTES;
        String truncated = text.substring(0, Math.min(maxChars, text.length()));
        int originalKB = (int) Math.ceil(bytes.length / 1024.0);
        truncated = truncated + "\n... [内容已截断，原 " + originalKB + " KB]";
        return new ExcelParseResult(truncated, sheetCount);
    }

    public static class ExcelParseResult {
        public final String text;
        public final int sheetCount;

        public ExcelParseResult(String text, int sheetCount) {
            this.text = text;
            this.sheetCount = sheetCount;
        }
    }
}