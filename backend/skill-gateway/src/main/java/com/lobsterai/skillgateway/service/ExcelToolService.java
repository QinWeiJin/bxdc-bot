package com.lobsterai.skillgateway.service;

import com.lobsterai.skillgateway.config.FtpConfig;
import com.lobsterai.skillgateway.dto.ExcelOperationResult;
import com.lobsterai.skillgateway.dto.ExcelOperationResult.*;
import com.lobsterai.skillgateway.entity.UserFile;
import com.lobsterai.skillgateway.exception.ExcelParseException;
import com.lobsterai.skillgateway.mapper.UserFileMapper;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.*;

@Service
public class ExcelToolService {

    private static final Logger log = LoggerFactory.getLogger(ExcelToolService.class);

    private final FtpFileService ftpFileService;
    private final FtpConfig ftpConfig;
    private final UserFileMapper userFileMapper;

    public ExcelToolService(FtpFileService ftpFileService, FtpConfig ftpConfig, UserFileMapper userFileMapper) {
        this.ftpFileService = ftpFileService;
        this.ftpConfig = ftpConfig;
        this.userFileMapper = userFileMapper;
    }

    public ExcelOperationResult read(Long fileId, String userId) {
        try {
            UserFile userFile = userFileMapper.selectById(fileId);
            if (userFile == null) {
                return ExcelOperationResult.failure("read", "文件不存在");
            }

            byte[] fileBytes = downloadFileBytes(userFile);
            Workbook wb = createWorkbook(fileBytes, userFile.getOriginalFileName());

            Sheet sheet = wb.getSheetAt(0);
            SheetData sheetData = extractSheetData(sheet);
            wb.close();

            return ExcelOperationResult.success("read", sheetData);
        } catch (Exception e) {
            log.error("Read operation failed", e);
            return ExcelOperationResult.failure("read", e.getMessage());
        }
    }

    private byte[] downloadFileBytes(UserFile userFile) throws IOException {
        ByteArrayOutputStream baos = ftpFileService.downloadFile(userFile.getUserId(), userFile.getFileName());
        return baos.toByteArray();
    }

    public ExcelOperationResult write(SheetData sheetData, String fileName, String userId) {
        try {
            Workbook wb = new XSSFWorkbook();
            Sheet sheet = wb.createSheet(sheetData.getSheetName() != null ? sheetData.getSheetName() : "Sheet1");

            int rowIdx = 0;
            if (sheetData.getColumns() != null) {
                Row headerRow = sheet.createRow(rowIdx++);
                for (int i = 0; i < sheetData.getColumns().size(); i++) {
                    Cell cell = headerRow.createCell(i);
                    cell.setCellValue(sheetData.getColumns().get(i));
                }
            }

            if (sheetData.getRows() != null) {
                for (List<Object> rowData : sheetData.getRows()) {
                    Row row = sheet.createRow(rowIdx++);
                    for (int i = 0; i < rowData.size(); i++) {
                        Cell cell = row.createCell(i);
                        Object value = rowData.get(i);
                        if (value == null) {
                            cell.setBlank();
                        } else if (value instanceof Number) {
                            cell.setCellValue(((Number) value).doubleValue());
                        } else {
                            cell.setCellValue(value.toString());
                        }
                    }
                }
            }

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            wb.write(baos);
            wb.close();

            // 上传文件
            ByteArrayInputStream bais = new ByteArrayInputStream(baos.toByteArray());
            String ftpPath = ftpFileService.uploadFile(userId, fileName, bais);

            // 创建 UserFile 记录
            UserFile userFile = new UserFile();
            userFile.setUserId(userId);
            userFile.setOriginalFileName(fileName);
            userFile.setFileName(extractStorageFileName(ftpPath));
            userFile.setFileSize((long) baos.size());
            userFile.setFileType(extractExtension(fileName));
            userFile.setFtpPath(ftpPath);
            userFile.setUploadTime(LocalDateTime.now());
            userFile.setIsToolGenerated(1);
            userFileMapper.insert(userFile);

            String downloadUrl = ftpConfig.buildDownloadUrl(userFile.getId());
            return ExcelOperationResult.successWithFile("write", downloadUrl);
        } catch (Exception e) {
            log.error("Write operation failed", e);
            return ExcelOperationResult.failure("write", e.getMessage());
        }
    }

    public ExcelOperationResult filter(Long fileId, String userId, List<FilterCriteria> criteria) {
        try {
            UserFile userFile = userFileMapper.selectById(fileId);
            byte[] fileBytes = downloadFileBytes(userFile);
            Workbook wb = createWorkbook(fileBytes, userFile.getOriginalFileName());

            Sheet sheet = wb.getSheetAt(0);
            SheetData sheetData = extractSheetData(sheet);

            List<String> columns = sheetData.getColumns();
            List<List<Object>> filteredRows = new ArrayList<>();

            for (List<Object> row : sheetData.getRows()) {
                boolean match = true;
                for (FilterCriteria c : criteria) {
                    int colIdx = findColumnIndex(columns, c.getColumn());
                    if (colIdx < 0) continue;

                    Object cellValue = row.size() > colIdx ? row.get(colIdx) : null;
                    if (!matches(cellValue, c.getOperator(), c.getValue())) {
                        match = false;
                        break;
                    }
                }
                if (match) {
                    filteredRows.add(row);
                }
            }

            wb.close();
            SheetData result = new SheetData(sheetData.getSheetName(), columns, filteredRows);
            return ExcelOperationResult.success("filter", result);
        } catch (Exception e) {
            log.error("Filter operation failed", e);
            return ExcelOperationResult.failure("filter", e.getMessage());
        }
    }

    public ExcelOperationResult sort(Long fileId, String userId, List<SortSpec> sortSpecs) {
        try {
            UserFile userFile = userFileMapper.selectById(fileId);
            byte[] fileBytes = downloadFileBytes(userFile);
            Workbook wb = createWorkbook(fileBytes, userFile.getOriginalFileName());

            Sheet sheet = wb.getSheetAt(0);
            SheetData sheetData = extractSheetData(sheet);

            List<String> columns = sheetData.getColumns();
            List<List<Object>> rows = new ArrayList<>(sheetData.getRows());

            Collections.sort(rows, new RowComparator(columns, sortSpecs));

            wb.close();
            SheetData result = new SheetData(sheetData.getSheetName(), columns, rows);
            return ExcelOperationResult.success("sort", result);
        } catch (Exception e) {
            log.error("Sort operation failed", e);
            return ExcelOperationResult.failure("sort", e.getMessage());
        }
    }

    public ExcelOperationResult aggregate(Long fileId, String userId, String groupBy, List<AggregationSpec> aggregations) {
        try {
            UserFile userFile = userFileMapper.selectById(fileId);
            byte[] fileBytes = downloadFileBytes(userFile);
            Workbook wb = createWorkbook(fileBytes, userFile.getOriginalFileName());

            Sheet sheet = wb.getSheetAt(0);
            SheetData sheetData = extractSheetData(sheet);

            List<String> columns = sheetData.getColumns();
            int groupByIdx = findColumnIndex(columns, groupBy);
            if (groupByIdx < 0) {
                wb.close();
                return ExcelOperationResult.failure("aggregate", "分组列不存在: " + groupBy);
            }

            Map<Object, List<List<Object>>> groups = new LinkedHashMap<>();
            for (List<Object> row : sheetData.getRows()) {
                Object key = row.size() > groupByIdx ? row.get(groupByIdx) : null;
                groups.computeIfAbsent(key, k -> new ArrayList<>()).add(row);
            }

            List<String> resultColumns = new ArrayList<>();
            resultColumns.add(groupBy);
            for (AggregationSpec agg : aggregations) {
                resultColumns.add(agg.getColumn() + "_" + agg.getFunction());
            }

            List<List<Object>> resultRows = new ArrayList<>();
            for (Map.Entry<Object, List<List<Object>>> entry : groups.entrySet()) {
                List<Object> resultRow = new ArrayList<>();
                resultRow.add(entry.getKey());

                for (AggregationSpec agg : aggregations) {
                    int colIdx = findColumnIndex(columns, agg.getColumn());
                    Object aggResult = calculateAggregation(entry.getValue(), colIdx, agg.getFunction());
                    resultRow.add(aggResult);
                }
                resultRows.add(resultRow);
            }

            wb.close();
            SheetData result = new SheetData(sheetData.getSheetName() + "_aggregated", resultColumns, resultRows);
            return ExcelOperationResult.success("aggregate", result);
        } catch (Exception e) {
            log.error("Aggregate operation failed", e);
            return ExcelOperationResult.failure("aggregate", e.getMessage());
        }
    }

    public ExcelOperationResult pivot(Long fileId, String userId, String rowField, String colField, String valueField) {
        try {
            UserFile userFile = userFileMapper.selectById(fileId);
            byte[] fileBytes = downloadFileBytes(userFile);
            Workbook wb = createWorkbook(fileBytes, userFile.getOriginalFileName());

            Sheet sheet = wb.getSheetAt(0);
            SheetData sheetData = extractSheetData(sheet);

            List<String> columns = sheetData.getColumns();
            int rowIdx = findColumnIndex(columns, rowField);
            int colIdx = findColumnIndex(columns, colField);
            int valIdx = findColumnIndex(columns, valueField);

            if (rowIdx < 0 || colIdx < 0 || valIdx < 0) {
                wb.close();
                return ExcelOperationResult.failure("pivot", "指定的字段不存在");
            }

            Set<Object> rowValues = new LinkedHashSet<>();
            Set<Object> colValues = new LinkedHashSet<>();

            for (List<Object> row : sheetData.getRows()) {
                rowValues.add(row.size() > rowIdx ? row.get(rowIdx) : null);
                colValues.add(row.size() > colIdx ? row.get(colIdx) : null);
            }

            List<Object> rowList = new ArrayList<>(rowValues);
            List<Object> colList = new ArrayList<>(colValues);

            Map<String, Map<String, Double>> pivotData = new LinkedHashMap<>();
            for (Object rv : rowValues) {
                pivotData.put(String.valueOf(rv), new LinkedHashMap<>());
                for (Object cv : colValues) {
                    pivotData.get(String.valueOf(rv)).put(String.valueOf(cv), 0.0);
                }
            }

            for (List<Object> row : sheetData.getRows()) {
                Object rv = row.size() > rowIdx ? row.get(rowIdx) : null;
                Object cv = row.size() > colIdx ? row.get(colIdx) : null;
                Object v = row.size() > valIdx ? row.get(valIdx) : null;

                double value = v instanceof Number ? ((Number) v).doubleValue() : 0;
                pivotData.get(String.valueOf(rv)).merge(String.valueOf(cv), value, Double::sum);
            }

            List<String> resultColumns = new ArrayList<>();
            resultColumns.add(rowField);
            for (Object cv : colList) {
                resultColumns.add(String.valueOf(cv));
            }

            List<List<Object>> resultRows = new ArrayList<>();
            for (Object rv : rowList) {
                List<Object> resultRow = new ArrayList<>();
                resultRow.add(rv);
                for (Object cv : colList) {
                    resultRow.add(pivotData.get(String.valueOf(rv)).get(String.valueOf(cv)));
                }
                resultRows.add(resultRow);
            }

            wb.close();
            SheetData result = new SheetData(sheetData.getSheetName() + "_pivot", resultColumns, resultRows);
            return ExcelOperationResult.success("pivot", result);
        } catch (Exception e) {
            log.error("Pivot operation failed", e);
            return ExcelOperationResult.failure("pivot", e.getMessage());
        }
    }

    public ExcelOperationResult calculate(Long fileId, String userId, List<CalculateExpression> expressions) {
        try {
            UserFile userFile = userFileMapper.selectById(fileId);
            byte[] fileBytes = downloadFileBytes(userFile);
            Workbook wb = createWorkbook(fileBytes, userFile.getOriginalFileName());

            Sheet sheet = wb.getSheetAt(0);
            SheetData sheetData = extractSheetData(sheet);

            List<String> columns = new ArrayList<>(sheetData.getColumns());
            List<List<Object>> rows = new ArrayList<>();

            for (CalculateExpression expr : expressions) {
                columns.add(expr.getNewColumn());
            }

            for (List<Object> row : sheetData.getRows()) {
                List<Object> newRow = new ArrayList<>(row);
                for (CalculateExpression expr : expressions) {
                    Object result = evaluateExpression(row, sheetData.getColumns(), expr.getExpression());
                    newRow.add(result);
                }
                rows.add(newRow);
            }

            wb.close();
            SheetData result = new SheetData(sheetData.getSheetName(), columns, rows);
            return ExcelOperationResult.success("calculate", result);
        } catch (Exception e) {
            log.error("Calculate operation failed", e);
            return ExcelOperationResult.failure("calculate", e.getMessage());
        }
    }

    public ExcelOperationResult selectColumns(Long fileId, String userId, List<String> columnNames) {
        try {
            UserFile userFile = userFileMapper.selectById(fileId);
            byte[] fileBytes = downloadFileBytes(userFile);
            Workbook wb = createWorkbook(fileBytes, userFile.getOriginalFileName());

            Sheet sheet = wb.getSheetAt(0);
            SheetData sheetData = extractSheetData(sheet);

            List<String> allColumns = sheetData.getColumns();
            List<Integer> selectedIndices = new ArrayList<>();
            List<String> selectedColumns = new ArrayList<>();

            for (String colName : columnNames) {
                int idx = findColumnIndex(allColumns, colName);
                if (idx >= 0) {
                    selectedIndices.add(idx);
                    selectedColumns.add(colName);
                }
            }

            List<List<Object>> resultRows = new ArrayList<>();
            for (List<Object> row : sheetData.getRows()) {
                List<Object> newRow = new ArrayList<>();
                for (Integer idx : selectedIndices) {
                    newRow.add(row.size() > idx ? row.get(idx) : null);
                }
                resultRows.add(newRow);
            }

            wb.close();
            SheetData result = new SheetData(sheetData.getSheetName(), selectedColumns, resultRows);
            return ExcelOperationResult.success("select_columns", result);
        } catch (Exception e) {
            log.error("Select columns operation failed", e);
            return ExcelOperationResult.failure("select_columns", e.getMessage());
        }
    }

    public ExcelOperationResult clean(Long fileId, String userId, String strategy) {
        try {
            UserFile userFile = userFileMapper.selectById(fileId);
            byte[] fileBytes = downloadFileBytes(userFile);
            Workbook wb = createWorkbook(fileBytes, userFile.getOriginalFileName());

            Sheet sheet = wb.getSheetAt(0);
            SheetData sheetData = extractSheetData(sheet);

            List<List<Object>> cleanedRows = new ArrayList<>();

            if ("remove_empty_rows".equalsIgnoreCase(strategy)) {
                for (List<Object> row : sheetData.getRows()) {
                    boolean hasValue = false;
                    for (Object cell : row) {
                        if (cell != null && !cell.toString().trim().isEmpty()) {
                            hasValue = true;
                            break;
                        }
                    }
                    if (hasValue) {
                        cleanedRows.add(row);
                    }
                }
            } else if ("fill_empty_with_zero".equalsIgnoreCase(strategy)) {
                for (List<Object> row : sheetData.getRows()) {
                    List<Object> newRow = new ArrayList<>();
                    for (Object cell : row) {
                        if (cell == null || cell.toString().trim().isEmpty()) {
                            newRow.add(0);
                        } else {
                            newRow.add(cell);
                        }
                    }
                    cleanedRows.add(newRow);
                }
            } else if ("trim_strings".equalsIgnoreCase(strategy)) {
                for (List<Object> row : sheetData.getRows()) {
                    List<Object> newRow = new ArrayList<>();
                    for (Object cell : row) {
                        if (cell instanceof String) {
                            newRow.add(((String) cell).trim());
                        } else {
                            newRow.add(cell);
                        }
                    }
                    cleanedRows.add(newRow);
                }
            }

            wb.close();
            SheetData result = new SheetData(sheetData.getSheetName(), sheetData.getColumns(), cleanedRows);
            return ExcelOperationResult.success("clean", result);
        } catch (Exception e) {
            log.error("Clean operation failed", e);
            return ExcelOperationResult.failure("clean", e.getMessage());
        }
    }

    public ExcelOperationResult merge(Long fileId1, Long fileId2, String userId, String joinKey) {
        try {
            UserFile uf1 = userFileMapper.selectById(fileId1);
            UserFile uf2 = userFileMapper.selectById(fileId2);

            byte[] bytes1 = downloadFileBytes(uf1);
            byte[] bytes2 = downloadFileBytes(uf2);

            Workbook wb1 = createWorkbook(bytes1, uf1.getOriginalFileName());
            Workbook wb2 = createWorkbook(bytes2, uf2.getOriginalFileName());

            SheetData data1 = extractSheetData(wb1.getSheetAt(0));
            SheetData data2 = extractSheetData(wb2.getSheetAt(0));

            int keyIdx1 = findColumnIndex(data1.getColumns(), joinKey);
            int keyIdx2 = findColumnIndex(data2.getColumns(), joinKey);

            if (keyIdx1 < 0 || keyIdx2 < 0) {
                wb1.close();
                wb2.close();
                return ExcelOperationResult.failure("merge", "连接键不存在: " + joinKey);
            }

            Map<Object, List<Object>> map2 = new LinkedHashMap<>();
            for (List<Object> row : data2.getRows()) {
                Object key = row.size() > keyIdx2 ? row.get(keyIdx2) : null;
                List<Object> rowWithoutKey = new ArrayList<>();
                for (int i = 0; i < row.size(); i++) {
                    if (i != keyIdx2) {
                        rowWithoutKey.add(row.get(i));
                    }
                }
                map2.put(key, rowWithoutKey);
            }

            List<String> resultColumns = new ArrayList<>(data1.getColumns());
            for (int i = 0; i < data2.getColumns().size(); i++) {
                if (i != keyIdx2) {
                    resultColumns.add(data2.getColumns().get(i));
                }
            }

            List<List<Object>> resultRows = new ArrayList<>();
            for (List<Object> row : data1.getRows()) {
                Object key = row.size() > keyIdx1 ? row.get(keyIdx1) : null;
                List<Object> row2 = map2.get(key);

                List<Object> mergedRow = new ArrayList<>(row);
                if (row2 != null) {
                    mergedRow.addAll(row2);
                } else {
                    for (int i = 0; i < data2.getColumns().size() - 1; i++) {
                        mergedRow.add(null);
                    }
                }
                resultRows.add(mergedRow);
            }

            wb1.close();
            wb2.close();
            SheetData result = new SheetData("merged", resultColumns, resultRows);
            return ExcelOperationResult.success("merge", result);
        } catch (Exception e) {
            log.error("Merge operation failed", e);
            return ExcelOperationResult.failure("merge", e.getMessage());
        }
    }

    public ExcelOperationResult convertFormat(Long fileId, String userId, String targetFormat) {
        try {
            UserFile userFile = userFileMapper.selectById(fileId);
            byte[] fileBytes = downloadFileBytes(userFile);

            Workbook wb = createWorkbook(fileBytes, userFile.getOriginalFileName());
            String newFileName = changeExtension(userFile.getOriginalFileName(), targetFormat);

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            if ("csv".equalsIgnoreCase(targetFormat)) {
                Sheet sheet = wb.getSheetAt(0);
                DataFormatter formatter = new DataFormatter();

                StringBuilder sb = new StringBuilder();
                Row headerRow = sheet.getRow(0);
                if (headerRow != null) {
                    for (int i = 0; i < headerRow.getLastCellNum(); i++) {
                        if (i > 0) sb.append(",");
                        Cell cell = headerRow.getCell(i);
                        sb.append(escapeCsvValue(formatter.formatCellValue(cell)));
                    }
                    sb.append("\n");
                }

                for (int i = 1; i <= sheet.getLastRowNum(); i++) {
                    Row row = sheet.getRow(i);
                    if (row == null) continue;
                    for (int j = 0; j < row.getLastCellNum(); j++) {
                        if (j > 0) sb.append(",");
                        Cell cell = row.getCell(j);
                        sb.append(escapeCsvValue(formatter.formatCellValue(cell)));
                    }
                    sb.append("\n");
                }
                baos.write(sb.toString().getBytes(StandardCharsets.UTF_8));
            } else {
                wb.write(baos);
            }
            wb.close();

            // 上传文件
            ByteArrayInputStream bais = new ByteArrayInputStream(baos.toByteArray());
            String ftpPath = ftpFileService.uploadFile(userId, newFileName, bais);

            // 创建 UserFile 记录
            UserFile newFile = new UserFile();
            newFile.setUserId(userId);
            newFile.setOriginalFileName(newFileName);
            newFile.setFileName(extractStorageFileName(ftpPath));
            newFile.setFileSize((long) baos.size());
            newFile.setFileType(extractExtension(newFileName));
            newFile.setFtpPath(ftpPath);
            newFile.setUploadTime(LocalDateTime.now());
            userFileMapper.insert(newFile);

            String downloadUrl = ftpConfig.buildDownloadUrl(newFile.getId());
            return ExcelOperationResult.successWithFile("convert_format", downloadUrl);
        } catch (Exception e) {
            log.error("Convert format operation failed", e);
            return ExcelOperationResult.failure("convert_format", e.getMessage());
        }
    }

    public ExcelOperationResult applyStyle(Long fileId, String userId, String conditionColumn, String conditionValue, String color) {
        try {
            UserFile userFile = userFileMapper.selectById(fileId);
            byte[] fileBytes = downloadFileBytes(userFile);
            Workbook wb = createWorkbook(fileBytes, userFile.getOriginalFileName());

            Sheet sheet = wb.getSheetAt(0);
            SheetData sheetData = extractSheetData(sheet);

            int colIdx = findColumnIndex(sheetData.getColumns(), conditionColumn);
            if (colIdx < 0) {
                wb.close();
                return ExcelOperationResult.failure("apply_style", "列不存在: " + conditionColumn);
            }

            CellStyle style = wb.createCellStyle();
            Font font = wb.createFont();
            font.setColor(getColorIndex(color));
            style.setFont(font);

            int rowIdx = 1;
            for (List<Object> row : sheetData.getRows()) {
                Row sheetRow = sheet.getRow(rowIdx);
                if (sheetRow == null) {
                    sheetRow = sheet.createRow(rowIdx);
                }

                Object cellValue = row.size() > colIdx ? row.get(colIdx) : null;
                if (conditionValue.equals(String.valueOf(cellValue))) {
                    for (int i = 0; i < sheetData.getColumns().size(); i++) {
                        Cell cell = sheetRow.getCell(i);
                        if (cell == null) {
                            cell = sheetRow.createCell(i);
                        }
                        cell.setCellStyle(style);
                    }
                }
                rowIdx++;
            }

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            wb.write(baos);
            wb.close();

            // 上传文件
            ByteArrayInputStream bais = new ByteArrayInputStream(baos.toByteArray());
            String ftpPath = ftpFileService.uploadFile(userId, userFile.getOriginalFileName(), bais);

            // 创建 UserFile 记录
            UserFile newFile = new UserFile();
            newFile.setUserId(userId);
            newFile.setOriginalFileName(FtpFileService.getTempDisplayFileName(userFile.getOriginalFileName()));
            newFile.setFileName(extractStorageFileName(ftpPath));
            newFile.setFileSize((long) baos.size());
            newFile.setFileType(extractExtension(userFile.getOriginalFileName()));
            newFile.setFtpPath(ftpPath);
            newFile.setUploadTime(LocalDateTime.now());
            newFile.setIsToolGenerated(1);
            userFileMapper.insert(newFile);

            String downloadUrl = ftpConfig.buildDownloadUrl(newFile.getId());
            return ExcelOperationResult.successWithFile("apply_style", downloadUrl);
        } catch (Exception e) {
            log.error("Apply style operation failed", e);
            return ExcelOperationResult.failure("apply_style", e.getMessage());
        }
    }

    public ExcelOperationResult validate(Long fileId, String userId, List<Map<String, Object>> rules) {
        try {
            UserFile userFile = userFileMapper.selectById(fileId);
            byte[] fileBytes = downloadFileBytes(userFile);
            Workbook wb = createWorkbook(fileBytes, userFile.getOriginalFileName());

            Sheet sheet = wb.getSheetAt(0);
            SheetData sheetData = extractSheetData(sheet);

            List<ValidationError> errors = new ArrayList<>();
            int rowIdx = 1;

            for (List<Object> row : sheetData.getRows()) {
                for (Map<String, Object> rule : rules) {
                    String column = (String) rule.get("column");
                    String type = (String) rule.get("type");
                    Integer minLength = rule.get("minLength") != null ? ((Number) rule.get("minLength")).intValue() : null;
                    Integer maxLength = rule.get("maxLength") != null ? ((Number) rule.get("maxLength")).intValue() : null;
                    Boolean required = rule.get("required") != null ? (Boolean) rule.get("required") : false;

                    int colIdx = findColumnIndex(sheetData.getColumns(), column);
                    if (colIdx < 0) continue;

                    Object cellValue = row.size() > colIdx ? row.get(colIdx) : null;

                    if (required && (cellValue == null || cellValue.toString().trim().isEmpty())) {
                        errors.add(new ValidationError(rowIdx, column, "必填字段为空"));
                        continue;
                    }

                    if (cellValue == null) continue;

                    String valueStr = String.valueOf(cellValue);

                    if ("email".equalsIgnoreCase(type) && !isValidEmail(valueStr)) {
                        errors.add(new ValidationError(rowIdx, column, "不是有效的邮箱地址"));
                    } else if ("phone".equalsIgnoreCase(type) && !isValidPhone(valueStr)) {
                        errors.add(new ValidationError(rowIdx, column, "不是有效的手机号"));
                    } else if ("number".equalsIgnoreCase(type) && !isValidNumber(valueStr)) {
                        errors.add(new ValidationError(rowIdx, column, "不是有效的数字"));
                    } else if (minLength != null && valueStr.length() < minLength) {
                        errors.add(new ValidationError(rowIdx, column, "长度不足，最小需要" + minLength + "个字符"));
                    } else if (maxLength != null && valueStr.length() > maxLength) {
                        errors.add(new ValidationError(rowIdx, column, "长度超限，最大允许" + maxLength + "个字符"));
                    }
                }
                rowIdx++;
            }

            wb.close();

            ExcelOperationResult result = ExcelOperationResult.success("validate", null);
            result.setSuccess(errors.isEmpty());
            if (!errors.isEmpty()) {
                result.setMessage("校验失败，共发现 " + errors.size() + " 个错误");
            }
            return result;
        } catch (Exception e) {
            log.error("Validate operation failed", e);
            return ExcelOperationResult.failure("validate", e.getMessage());
        }
    }

    private Workbook createWorkbook(byte[] fileBytes, String fileName) throws IOException {
        String lower = fileName.toLowerCase();
        ByteArrayInputStream bais = new ByteArrayInputStream(fileBytes);
        if (lower.endsWith(".xlsx")) {
            return new XSSFWorkbook(bais);
        } else if (lower.endsWith(".xls")) {
            return new HSSFWorkbook(bais);
        } else if (lower.endsWith(".csv")) {
            return csvToWorkbook(fileBytes);
        }
        throw new ExcelParseException("EXCEL_UNSUPPORTED_TYPE", "不支持的格式: " + fileName);
    }

    private Workbook csvToWorkbook(byte[] fileBytes) throws IOException {
        String content = new String(fileBytes, StandardCharsets.UTF_8);
        String[] lines = content.split("\\r?\\n");

        Workbook wb = new XSSFWorkbook();
        Sheet sheet = wb.createSheet("Sheet1");

        int rowIdx = 0;
        for (String line : lines) {
            if (line.trim().isEmpty()) continue;

            Row row = sheet.createRow(rowIdx++);
            List<String> values = parseCsvLine(line);

            int colIdx = 0;
            for (String value : values) {
                Cell cell = row.createCell(colIdx++);
                try {
                    double numValue = Double.parseDouble(value);
                    if (numValue == Math.floor(numValue)) {
                        cell.setCellValue((long) numValue);
                    } else {
                        cell.setCellValue(numValue);
                    }
                } catch (NumberFormatException e) {
                    cell.setCellValue(value);
                }
            }
        }
        return wb;
    }

    private List<String> parseCsvLine(String line) {
        List<String> values = new ArrayList<>();
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
        return values;
    }

    private SheetData extractSheetData(Sheet sheet) {
        List<String> columns = new ArrayList<>();
        List<List<Object>> rows = new ArrayList<>();

        DataFormatter formatter = new DataFormatter();
        int maxCol = 0;

        for (Row row : sheet) {
            int lastCol = row.getLastCellNum();
            maxCol = Math.max(maxCol, lastCol);
        }

        boolean isFirst = true;
        for (Row row : sheet) {
            List<Object> rowData = new ArrayList<>();
            for (int i = 0; i < maxCol; i++) {
                Cell cell = row.getCell(i, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
                if (isFirst) {
                    columns.add(cell != null ? formatter.formatCellValue(cell) : "Column" + (i + 1));
                } else {
                    rowData.add(getCellValue(cell));
                }
            }
            if (!isFirst) {
                rows.add(rowData);
            }
            isFirst = false;
        }

        return new SheetData(sheet.getSheetName(), columns, rows);
    }

    private Object getCellValue(Cell cell) {
        if (cell == null) return null;

        switch (cell.getCellType()) {
            case NUMERIC:
                double numValue = cell.getNumericCellValue();
                if (numValue == Math.floor(numValue)) {
                    return (long) numValue;
                }
                return numValue;
            case STRING:
                return cell.getStringCellValue();
            case BOOLEAN:
                return cell.getBooleanCellValue();
            case FORMULA:
                try {
                    return cell.getNumericCellValue();
                } catch (Exception e) {
                    return cell.getStringCellValue();
                }
            default:
                return null;
        }
    }

    private int findColumnIndex(List<String> columns, String columnName) {
        for (int i = 0; i < columns.size(); i++) {
            if (columns.get(i).equalsIgnoreCase(columnName)) {
                return i;
            }
        }
        return -1;
    }

    private boolean matches(Object cellValue, String operator, Object filterValue) {
        if (cellValue == null && filterValue == null) return true;
        if (cellValue == null || filterValue == null) return false;

        String cellStr = String.valueOf(cellValue).toLowerCase();
        String filterStr = String.valueOf(filterValue).toLowerCase();

        switch (operator.toLowerCase()) {
            case "equals":
            case "=":
                return cellStr.equals(filterStr);
            case "contains":
                return cellStr.contains(filterStr);
            case "starts_with":
                return cellStr.startsWith(filterStr);
            case "ends_with":
                return cellStr.endsWith(filterStr);
            case "greater_than":
            case ">":
                return compareNumbers(cellValue, filterValue) > 0;
            case "less_than":
            case "<":
                return compareNumbers(cellValue, filterValue) < 0;
            case "greater_than_or_equal":
            case ">=":
                return compareNumbers(cellValue, filterValue) >= 0;
            case "less_than_or_equal":
            case "<=":
                return compareNumbers(cellValue, filterValue) <= 0;
            case "not_equals":
            case "!=":
                return !cellStr.equals(filterStr);
            default:
                return false;
        }
    }

    private int compareNumbers(Object a, Object b) {
        try {
            double numA = a instanceof Number ? ((Number) a).doubleValue() : Double.parseDouble(String.valueOf(a));
            double numB = b instanceof Number ? ((Number) b).doubleValue() : Double.parseDouble(String.valueOf(b));
            return Double.compare(numA, numB);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private Object calculateAggregation(List<List<Object>> rows, int colIdx, String function) {
        double sum = 0;
        double min = Double.MAX_VALUE;
        double max = Double.MIN_VALUE;
        int count = 0;
        List<Double> values = new ArrayList<>();

        for (List<Object> row : rows) {
            if (row.size() > colIdx) {
                Object value = row.get(colIdx);
                if (value instanceof Number) {
                    double num = ((Number) value).doubleValue();
                    sum += num;
                    min = Math.min(min, num);
                    max = Math.max(max, num);
                    values.add(num);
                    count++;
                }
            }
        }

        switch (function.toLowerCase()) {
            case "sum":
                return sum;
            case "avg":
            case "average":
                return count > 0 ? sum / count : 0;
            case "min":
                return count > 0 ? min : null;
            case "max":
                return count > 0 ? max : null;
            case "count":
                return count;
            case "count_distinct":
                Set<Object> distinct = new HashSet<>();
                for (List<Object> row : rows) {
                    if (row.size() > colIdx) {
                        distinct.add(row.get(colIdx));
                    }
                }
                return distinct.size();
            default:
                return sum;
        }
    }

    private Object evaluateExpression(List<Object> row, List<String> columns, String expression) {
        try {
            for (int i = 0; i < columns.size(); i++) {
                String colName = columns.get(i);
                Object value = row.size() > i ? row.get(i) : null;
                String valueStr = value instanceof Number ? String.valueOf(value) : "\"" + String.valueOf(value) + "\"";
                expression = expression.replace("{" + colName + "}", valueStr);
            }

            return evaluateSimpleExpression(expression);
        } catch (Exception e) {
            log.warn("Expression evaluation failed: {}", expression, e);
            return null;
        }
    }

    private Object evaluateSimpleExpression(String expression) {
        expression = expression.trim();
        try {
            return Double.parseDouble(expression);
        } catch (NumberFormatException e) {
            return expression;
        }
    }

    private String changeExtension(String fileName, String newExt) {
        int dotIdx = fileName.lastIndexOf('.');
        if (dotIdx >= 0) {
            return fileName.substring(0, dotIdx + 1) + newExt;
        }
        return fileName + "." + newExt;
    }

    private String escapeCsvValue(String value) {
        if (value == null) return "";
        if (value.contains(",") || value.contains("\"") || value.contains("\n")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }

    private short getColorIndex(String color) {
        switch (color.toLowerCase()) {
            case "red":
                return IndexedColors.RED.getIndex();
            case "green":
                return IndexedColors.GREEN.getIndex();
            case "blue":
                return IndexedColors.BLUE.getIndex();
            case "yellow":
                return IndexedColors.YELLOW.getIndex();
            case "orange":
                return IndexedColors.ORANGE.getIndex();
            default:
                return IndexedColors.BLACK.getIndex();
        }
    }

    private boolean isValidEmail(String email) {
        return email != null && email.matches("[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}");
    }

    private boolean isValidPhone(String phone) {
        return phone != null && phone.matches("1[3-9]\\d{9}");
    }

    private boolean isValidNumber(String value) {
        try {
            Double.parseDouble(value);
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private static class RowComparator implements Comparator<List<Object>> {
        private final List<String> columns;
        private final List<SortSpec> sortSpecs;

        public RowComparator(List<String> columns, List<SortSpec> sortSpecs) {
            this.columns = columns;
            this.sortSpecs = sortSpecs;
        }

        @Override
        public int compare(List<Object> row1, List<Object> row2) {
            for (SortSpec spec : sortSpecs) {
                int colIdx = findColumnIndex(columns, spec.getColumn());
                if (colIdx < 0) continue;

                Object val1 = row1.size() > colIdx ? row1.get(colIdx) : null;
                Object val2 = row2.size() > colIdx ? row2.get(colIdx) : null;

                int result = compareValues(val1, val2);
                if (result != 0) {
                    return "desc".equalsIgnoreCase(spec.getOrder()) ? -result : result;
                }
            }
            return 0;
        }

        private int findColumnIndex(List<String> cols, String name) {
            for (int i = 0; i < cols.size(); i++) {
                if (cols.get(i).equalsIgnoreCase(name)) return i;
            }
            return -1;
        }

        private int compareValues(Object a, Object b) {
            if (a == null && b == null) return 0;
            if (a == null) return -1;
            if (b == null) return 1;

            if (a instanceof Number && b instanceof Number) {
                return Double.compare(((Number) a).doubleValue(), ((Number) b).doubleValue());
            }
            return String.valueOf(a).compareTo(String.valueOf(b));
        }
    }

    private String extractStorageFileName(String ftpPath) {
        if (ftpPath == null) return "";
        int lastSlash = ftpPath.lastIndexOf('/');
        if (lastSlash >= 0 && lastSlash < ftpPath.length() - 1) {
            return ftpPath.substring(lastSlash + 1);
        }
        return ftpPath;
    }

    private String extractExtension(String fileName) {
        if (fileName == null) return "";
        int lastDot = fileName.lastIndexOf('.');
        if (lastDot < 0 || lastDot == fileName.length() - 1) {
            return "";
        }
        return fileName.substring(lastDot + 1).toLowerCase();
    }
}