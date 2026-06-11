package com.lobsterai.skillgateway.service.parser;

import com.lobsterai.skillgateway.dto.FileParseResult;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Excel 文档解析器。
 * <p>
 * 支持 .xlsx (XSSF) 和 .xls (HSSF) 格式。
 * 提取 Sheet 信息、列定义、数据预览，产出结构化 JSON。
 * </p>
 */
@Component
public class ExcelParser implements FileParser {

    private static final Logger log = LoggerFactory.getLogger(ExcelParser.class);

    private static final int MAX_PREVIEW_ROWS = 10;
    private static final int MAX_SAMPLE_VALUES = 5;

    @Override
    public String supportedType() {
        return "xlsx"; // also handles "xls" via the router
    }

    @Override
    public FileParseResult parse(byte[] fileBytes, String fileName) throws Exception {
        String ext = extractExtension(fileName);
        if ("xlsx".equals(ext)) {
            return parseXlsx(fileBytes, fileName);
        } else if ("xls".equals(ext)) {
            return parseXls(fileBytes, fileName);
        } else if ("csv".equals(ext)) {
            return parseCsv(fileBytes, fileName);
        } else {
            throw new IllegalArgumentException("Unsupported Excel format: " + ext);
        }
    }

    // ========== XLSX (XSSF) ==========

    private FileParseResult parseXlsx(byte[] fileBytes, String fileName) throws Exception {
        FileParseResult result = new FileParseResult();
        result.setFileType("excel");

        Workbook workbook = new XSSFWorkbook(new ByteArrayInputStream(fileBytes));
        try {
            int numberOfSheets = workbook.getNumberOfSheets();
            result.setSheetCount(numberOfSheets);

            List<FileParseResult.SheetInfo> sheets = new ArrayList<FileParseResult.SheetInfo>();
            for (int i = 0; i < numberOfSheets; i++) {
                Sheet sheet = workbook.getSheetAt(i);
                FileParseResult.SheetInfo sheetInfo = parseSheet(sheet, i);
                sheets.add(sheetInfo);
            }
            result.setSheets(sheets);

            result.setIsEncrypted(false);
            result.setHasMacro(false);

        } finally {
            workbook.close();
        }
        return result;
    }

    // ========== XLS (HSSF) ==========

    private FileParseResult parseXls(byte[] fileBytes, String fileName) throws Exception {
        FileParseResult result = new FileParseResult();
        result.setFileType("excel");

        Workbook workbook = new HSSFWorkbook(new ByteArrayInputStream(fileBytes));
        try {
            int numberOfSheets = workbook.getNumberOfSheets();
            result.setSheetCount(numberOfSheets);

            List<FileParseResult.SheetInfo> sheets = new ArrayList<FileParseResult.SheetInfo>();
            for (int i = 0; i < numberOfSheets; i++) {
                Sheet sheet = workbook.getSheetAt(i);
                FileParseResult.SheetInfo sheetInfo = parseSheet(sheet, i);
                sheets.add(sheetInfo);
            }
            result.setSheets(sheets);

            result.setIsEncrypted(false);
            result.setHasMacro(false);

        } finally {
            workbook.close();
        }
        return result;
    }

    // ========== Sheet Parsing ==========

    private FileParseResult.SheetInfo parseSheet(Sheet sheet, int sheetIndex) {
        FileParseResult.SheetInfo sheetInfo = new FileParseResult.SheetInfo();
        sheetInfo.setSheetName(sheet.getSheetName());
        sheetInfo.setSheetIndex(sheetIndex);

        int firstRow = sheet.getFirstRowNum();
        int lastRow = sheet.getLastRowNum();
        int rowCount = (lastRow >= firstRow) ? (lastRow - firstRow + 1) : 0;
        sheetInfo.setRowCount(rowCount);

        // Determine column count from first row
        int colCount = 0;
        Row firstDataRow = sheet.getRow(firstRow);
        if (firstDataRow != null) {
            colCount = firstDataRow.getLastCellNum();
            if (colCount < 0) colCount = 0;
        }
        sheetInfo.setColCount(colCount);

        // Assume first row is header
        sheetInfo.setHasHeader(true);
        sheetInfo.setHeaderRowIndex(0);

        // Detect merged cells
        int mergedRegions = sheet.getNumMergedRegions();
        sheetInfo.setHasMergedCells(mergedRegions > 0);
        if (mergedRegions > 0) {
            List<String> mergedRanges = new ArrayList<String>();
            for (int i = 0; i < mergedRegions && i < 10; i++) {
                CellRangeAddress range = sheet.getMergedRegion(i);
                // Format: A1:C3 (top-left cell to bottom-right cell)
                String startCell = indexToLetter(range.getFirstColumn()) + (range.getFirstRow() + 1);
                String endCell = indexToLetter(range.getLastColumn()) + (range.getLastRow() + 1);
                mergedRanges.add(startCell + ":" + endCell);
            }
            sheetInfo.setMergedCellRanges(mergedRanges);
        }

        // Detect frozen panes (使用新版 POI API)
        int topRow = sheet.getTopRow();
        int leftCol = sheet.getLeftCol();
        if (topRow > 0 || leftCol > 0) {
            sheetInfo.setFrozenPanes(indexToLetter(leftCol) + (topRow + 1));
        }

        // Detect formulas and charts
        sheetInfo.setHasFormula(hasFormula(sheet));
        sheetInfo.setHasChart(sheet.getDrawingPatriarch() != null);
        sheetInfo.setHasPivotTable(false); // Not easily detectable

        // Parse columns
        List<FileParseResult.ColumnInfo> columns = parseColumns(sheet, firstRow, colCount);
        sheetInfo.setColumns(columns);

        // Calculate data row count (exclude header)
        int dataRowCount = (rowCount > 0) ? (rowCount - 1) : 0;
        sheetInfo.setDataRowCount(dataRowCount);

        return sheetInfo;
    }

    private List<FileParseResult.ColumnInfo> parseColumns(Sheet sheet, int headerRowIndex, int colCount) {
        List<FileParseResult.ColumnInfo> columns = new ArrayList<FileParseResult.ColumnInfo>();

        Row headerRow = sheet.getRow(headerRowIndex);
        if (headerRow == null) {
            return columns;
        }

        for (int colIndex = 0; colIndex < colCount; colIndex++) {
            FileParseResult.ColumnInfo columnInfo = new FileParseResult.ColumnInfo();
            columnInfo.setColIndex(colIndex);
            columnInfo.setColLetter(indexToLetter(colIndex));

            // Get header name
            Cell headerCell = headerRow.getCell(colIndex);
            if (headerCell != null) {
                columnInfo.setHeaderName(getCellStringValue(headerCell));
            } else {
                columnInfo.setHeaderName("Column" + (colIndex + 1));
            }

            // Infer type and collect statistics
            inferColumnTypeAndStats(sheet, colIndex, headerRowIndex + 1, columnInfo);

            columns.add(columnInfo);
        }

        return columns;
    }

    private void inferColumnTypeAndStats(Sheet sheet, int colIndex, int startRow, FileParseResult.ColumnInfo columnInfo) {
        int lastRow = sheet.getLastRowNum();
        int nullCount = 0;
        int numberCount = 0;
        int dateCount = 0;
        int stringCount = 0;
        Set<String> uniqueValues = new HashSet<String>();
        List<String> sampleValues = new ArrayList<String>();
        double sum = 0;
        double min = Double.MAX_VALUE;
        double max = Double.MIN_VALUE;
        String minDate = null;
        String maxDate = null;

        for (int rowIndex = startRow; rowIndex <= lastRow; rowIndex++) {
            Row row = sheet.getRow(rowIndex);
            if (row == null) {
                nullCount++;
                continue;
            }

            Cell cell = row.getCell(colIndex);
            if (cell == null || cell.getCellType() == CellType.BLANK) {
                nullCount++;
                continue;
            }

            String stringValue = getCellStringValue(cell);
            if (stringValue == null || stringValue.trim().isEmpty()) {
                nullCount++;
                continue;
            }

            // Track unique values and samples
            uniqueValues.add(stringValue);
            if (sampleValues.size() < MAX_SAMPLE_VALUES) {
                sampleValues.add(stringValue);
            }

            // Type detection
            if (cell.getCellType() == CellType.NUMERIC) {
                if (DateUtil.isCellDateFormatted(cell)) {
                    dateCount++;
                    String dateStr = getCellStringValue(cell);
                    if (minDate == null || dateStr.compareTo(minDate) < 0) {
                        minDate = dateStr;
                    }
                    if (maxDate == null || dateStr.compareTo(maxDate) > 0) {
                        maxDate = dateStr;
                    }
                } else {
                    numberCount++;
                    double value = cell.getNumericCellValue();
                    sum += value;
                    if (value < min) min = value;
                    if (value > max) max = value;
                }
            } else {
                stringCount++;
            }
        }

        int totalRows = lastRow - startRow + 1 - nullCount;
        columnInfo.setNullCount(nullCount);
        columnInfo.setUniqueCount(uniqueValues.size());
        columnInfo.setSampleValues(sampleValues);

        // Determine inferred type
        String inferredType;
        if (totalRows > 0) {
            double dateRatio = (double) dateCount / totalRows;
            double numberRatio = (double) numberCount / totalRows;

            if (dateRatio > 0.5) {
                inferredType = "datetime";
                columnInfo.setMinDate(minDate);
                columnInfo.setMaxDate(maxDate);
            } else if (numberRatio > 0.7) {
                inferredType = "float";
                // Add stats for numeric columns
                if (numberCount > 0) {
                    columnInfo.setStats(createStats(sum, min, max, numberCount));
                }
            } else {
                inferredType = "string";
                // Check for categorical data (low cardinality)
                if (uniqueValues.size() <= 20 && totalRows >= 10) {
                    inferredType = "category";
                    columnInfo.setCategories(new ArrayList<String>(uniqueValues));
                }
            }
        } else {
            inferredType = "string";
        }
        columnInfo.setInferredType(inferredType);
    }

    private java.util.Map<String, Object> createStats(double sum, double min, double max, int count) {
        java.util.Map<String, Object> stats = new java.util.HashMap<String, Object>();
        stats.put("min", min);
        stats.put("max", max);
        stats.put("mean", count > 0 ? sum / count : 0);
        return stats;
    }

    private boolean hasFormula(Sheet sheet) {
        for (Row row : sheet) {
            for (Cell cell : row) {
                if (cell.getCellType() == CellType.FORMULA) {
                    return true;
                }
            }
        }
        return false;
    }

    private String getCellStringValue(Cell cell) {
        if (cell == null) {
            return null;
        }

        switch (cell.getCellType()) {
            case STRING:
                return cell.getStringCellValue();
            case NUMERIC:
                if (DateUtil.isCellDateFormatted(cell)) {
                    return cell.getLocalDateTimeCellValue().toLocalDate().toString();
                }
                // Avoid scientific notation for large numbers
                double numValue = cell.getNumericCellValue();
                if (numValue == Math.floor(numValue) && !Double.isInfinite(numValue)) {
                    return String.valueOf((long) numValue);
                }
                return String.valueOf(numValue);
            case BOOLEAN:
                return String.valueOf(cell.getBooleanCellValue());
            case FORMULA:
                try {
                    return cell.getStringCellValue();
                } catch (Exception e) {
                    try {
                        return String.valueOf(cell.getNumericCellValue());
                    } catch (Exception e2) {
                        return cell.getCellFormula();
                    }
                }
            case BLANK:
                return "";
            default:
                return null;
        }
    }

    private String indexToLetter(int index) {
        StringBuilder sb = new StringBuilder();
        int num = index;
        while (num >= 0) {
            sb.insert(0, (char) ('A' + (num % 26)));
            num = num / 26 - 1;
        }
        return sb.toString();
    }

    private String extractExtension(String fileName) {
        if (fileName == null) return "";
        int dot = fileName.lastIndexOf('.');
        return dot < 0 ? "" : fileName.substring(dot + 1).toLowerCase();
    }

    // ========== CSV 解析 ==========

    private FileParseResult parseCsv(byte[] fileBytes, String fileName) throws Exception {
        FileParseResult result = new FileParseResult();
        result.setFileType("excel");

        // 解析 CSV 内容
        String content = new String(fileBytes, "UTF-8");
        String[] lines = content.split("\\r?\\n");
        
        // 过滤空行
        List<String> validLines = new ArrayList<String>();
        for (String line : lines) {
            if (line.trim().length() > 0) {
                validLines.add(line);
            }
        }
        
        if (validLines.isEmpty()) {
            result.setSheetCount(0);
            result.setSheets(new ArrayList<FileParseResult.SheetInfo>());
            return result;
        }

        // 创建 SheetInfo
        FileParseResult.SheetInfo sheetInfo = new FileParseResult.SheetInfo();
        sheetInfo.setSheetName("Sheet1");
        sheetInfo.setSheetIndex(0);
        sheetInfo.setRowCount(validLines.size());
        sheetInfo.setHasHeader(true);
        sheetInfo.setHeaderRowIndex(0);
        sheetInfo.setHasFormula(false);
        sheetInfo.setHasChart(false);
        sheetInfo.setHasMergedCells(false);
        sheetInfo.setHasPivotTable(false);

        // 解析表头
        String[] headers = parseCsvLine(validLines.get(0));
        sheetInfo.setColCount(headers.length);

        // 创建列信息
        List<FileParseResult.ColumnInfo> columns = new ArrayList<FileParseResult.ColumnInfo>();
        for (int i = 0; i < headers.length; i++) {
            FileParseResult.ColumnInfo colInfo = new FileParseResult.ColumnInfo();
            colInfo.setColIndex(i);
            colInfo.setColLetter(indexToLetter(i));
            colInfo.setHeaderName(headers[i].trim());
            colInfo.setInferredType(inferCsvColumnType(validLines, i));
            columns.add(colInfo);
        }
        sheetInfo.setColumns(columns);

        List<FileParseResult.SheetInfo> sheets = new ArrayList<FileParseResult.SheetInfo>();
        sheets.add(sheetInfo);
        result.setSheets(sheets);
        result.setSheetCount(1);
        result.setIsEncrypted(false);
        result.setHasMacro(false);

        return result;
    }

    private String[] parseCsvLine(String line) {
        List<String> values = new ArrayList<String>();
        StringBuilder current = new StringBuilder();
        boolean inQuotes = false;

        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') {
                if (inQuotes && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    current.append('"');
                    i++;
                } else {
                    inQuotes = !inQuotes;
                }
            } else if (c == ',' && !inQuotes) {
                values.add(current.toString());
                current = new StringBuilder();
            } else {
                current.append(c);
            }
        }
        values.add(current.toString());
        return values.toArray(new String[0]);
    }

    private String inferCsvColumnType(List<String> lines, int colIndex) {
        int numberCount = 0;
        int dateCount = 0;
        int stringCount = 0;
        int total = 0;

        for (int i = 1; i < lines.size() && i <= 20; i++) {
            String[] values = parseCsvLine(lines.get(i));
            if (colIndex >= values.length) continue;
            
            String val = values[colIndex].trim();
            if (val.isEmpty()) continue;
            
            total++;
            
            if (isNumeric(val)) {
                numberCount++;
            } else if (isDate(val)) {
                dateCount++;
            } else {
                stringCount++;
            }
        }

        if (total == 0) return "string";

        double dateRatio = (double) dateCount / total;
        double numberRatio = (double) numberCount / total;

        if (dateRatio > 0.5) {
            return "datetime";
        } else if (numberRatio > 0.7) {
            return "float";
        } else {
            return "string";
        }
    }

    private boolean isNumeric(String value) {
        try {
            Double.parseDouble(value);
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private boolean isDate(String value) {
        // 简单的日期格式检测
        String[] datePatterns = {"\\d{4}-\\d{2}-\\d{2}", "\\d{2}/\\d{2}/\\d{4}", "\\d{4}/\\d{2}/\\d{2}"};
        for (String pattern : datePatterns) {
            if (value.matches(pattern)) {
                return true;
            }
        }
        return false;
    }
}