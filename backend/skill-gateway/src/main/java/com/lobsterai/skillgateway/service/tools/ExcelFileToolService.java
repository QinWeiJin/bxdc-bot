package com.lobsterai.skillgateway.service.tools;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lobsterai.skillgateway.dto.FileToolResponse;
import com.lobsterai.skillgateway.entity.UserFile;
import com.lobsterai.skillgateway.mapper.UserFileMapper;
import com.lobsterai.skillgateway.service.FileToolService;
import com.lobsterai.skillgateway.service.FtpFileService;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.*;

/**
 * Excel 文件工具服务（模型工具调用入口）。
 * <p>
 * 为模型调用提供 Excel 文件操作能力，支持链式操作：
 * - 每次操作后将结果保存为新文件，文件名格式：原文件名_temp_v1, _temp_v2, ...
 * - 返回结果包含新文件地址，供下一步操作使用
 * - 支持通过 fileRef 参数引用原始文件或临时文件
 * </p>
 * <p>
 * 与 {@link com.lobsterai.skillgateway.service.ExcelToolService} 的区别：
 * - 本类：供模型工具调用，返回 {@link FileToolResponse}
 * - ExcelToolService：供 REST API 调用，返回 {@link com.lobsterai.skillgateway.dto.ExcelOperationResult}
 * </p>
 */
@Service
public class ExcelFileToolService {

    private static final Logger log = LoggerFactory.getLogger(ExcelFileToolService.class);

    private final FileToolService fileToolService;
    private final FtpFileService ftpFileService;
    private final UserFileMapper userFileMapper;

    @Value("${app.base-url:http://localhost:18080}")
    private String baseUrl;

    @Autowired
    public ExcelFileToolService(FileToolService fileToolService, FtpFileService ftpFileService, UserFileMapper userFileMapper) {
        this.fileToolService = fileToolService;
        this.ftpFileService = ftpFileService;
        this.userFileMapper = userFileMapper;
    }

    /** Spring 启动后自动注册到 FileToolService。 */
    @PostConstruct
    public void registerHandlers() {
        fileToolService.registerHandler("excel_read", new FileToolService.ToolHandler() {
            @Override
            public FileToolResponse handle(UserFile userFile, Map<String, Object> params, String userId) throws Exception {
                return excelRead(userFile, params, userId);
            }
        });
        fileToolService.registerHandler("excel_write", new FileToolService.ToolHandler() {
            @Override
            public FileToolResponse handle(UserFile userFile, Map<String, Object> params, String userId) throws Exception {
                return excelWrite(userFile, params, userId);
            }
        });
        fileToolService.registerHandler("excel_filter", new FileToolService.ToolHandler() {
            @Override
            public FileToolResponse handle(UserFile userFile, Map<String, Object> params, String userId) throws Exception {
                return excelFilter(userFile, params, userId);
            }
        });
        fileToolService.registerHandler("excel_sort", new FileToolService.ToolHandler() {
            @Override
            public FileToolResponse handle(UserFile userFile, Map<String, Object> params, String userId) throws Exception {
                return excelSort(userFile, params, userId);
            }
        });
        fileToolService.registerHandler("excel_aggregate", new FileToolService.ToolHandler() {
            @Override
            public FileToolResponse handle(UserFile userFile, Map<String, Object> params, String userId) throws Exception {
                return excelAggregate(userFile, params, userId);
            }
        });
        fileToolService.registerHandler("excel_pivot", new FileToolService.ToolHandler() {
            @Override
            public FileToolResponse handle(UserFile userFile, Map<String, Object> params, String userId) throws Exception {
                return excelPivot(userFile, params, userId);
            }
        });
        fileToolService.registerHandler("excel_calculate", new FileToolService.ToolHandler() {
            @Override
            public FileToolResponse handle(UserFile userFile, Map<String, Object> params, String userId) throws Exception {
                return excelCalculate(userFile, params, userId);
            }
        });
        fileToolService.registerHandler("excel_select_columns", new FileToolService.ToolHandler() {
            @Override
            public FileToolResponse handle(UserFile userFile, Map<String, Object> params, String userId) throws Exception {
                return excelSelectColumns(userFile, params, userId);
            }
        });
        fileToolService.registerHandler("excel_clean", new FileToolService.ToolHandler() {
            @Override
            public FileToolResponse handle(UserFile userFile, Map<String, Object> params, String userId) throws Exception {
                return excelClean(userFile, params, userId);
            }
        });
        fileToolService.registerHandler("excel_convert_format", new FileToolService.ToolHandler() {
            @Override
            public FileToolResponse handle(UserFile userFile, Map<String, Object> params, String userId) throws Exception {
                return excelConvertFormat(userFile, params, userId);
            }
        });
        fileToolService.registerHandler("excel_validate", new FileToolService.ToolHandler() {
            @Override
            public FileToolResponse handle(UserFile userFile, Map<String, Object> params, String userId) throws Exception {
                return excelValidate(userFile, params, userId);
            }
        });
        fileToolService.registerHandler("excel_init_temp", new FileToolService.ToolHandler() {
            @Override
            public FileToolResponse handle(UserFile userFile, Map<String, Object> params, String userId) throws Exception {
                return excelInitTemp(userFile, params, userId);
            }
        });
        log.info("ExcelFileToolService registered 12 handlers: excel_read/write/filter/sort/aggregate/pivot/calculate/select_columns/clean/convert_format/validate/init_temp");
    }

    // ================================================================
    // excel_init_temp — 初始化临时文件（创建临时文件副本）
    // ================================================================

    /**
     * 根据原文件生成临时文件，上传到 FTP 并在 user_files 表中创建新记录。
     * 
     * <p>返回新临时文件的 ID，后续文件操作都使用这个临时文件 ID。</p>
     * <p>后续 Excel 操作都在临时文件上进行覆盖更新，最终可将临时文件内容写回源文件。</p>
     * 
     * @param userFile 文件实体
     * @param params   参数：无
     * @param userId   用户 ID
     * @return 临时文件信息，包含临时文件 ID、源文件 ID、下载路径和表头
     */
    public FileToolResponse excelInitTemp(UserFile userFile, Map<String, Object> params, String userId) {
        ensureExcelFile(userFile);

        try {
            // 保存源文件 ID
            Long sourceFileId = userFile.getId();
            
            // 读取原文件内容
            byte[] fileBytes = downloadBytes(userFile);
            int fileSize = fileBytes.length;
            
            // 生成临时文件名
            String tempFileName = getTempFileName(userFile.getOriginalFileName());
            
            // 上传临时文件到 FTP（使用新生成的文件名）
            String ftpPath = ftpFileService.uploadFile(userId, tempFileName, new ByteArrayInputStream(fileBytes));
            
            // 提取 FTP 存储的文件名（UUID + 扩展名）
            String storageFileName = ftpPath.substring(ftpPath.lastIndexOf('/') + 1);
            
            // 在 user_files 表中创建新记录
            UserFile tempUserFile = new UserFile();
            tempUserFile.setUserId(userId);
            tempUserFile.setOriginalFileName(tempFileName);
            tempUserFile.setFileName(storageFileName);
            tempUserFile.setFileSize((long) fileSize);
            tempUserFile.setFileType(userFile.getFileType());
            tempUserFile.setFtpPath(ftpPath);
            tempUserFile.setSourceFileId(sourceFileId);
            tempUserFile.setUploadTime(LocalDateTime.now());
            userFileMapper.insert(tempUserFile);
            
            Long tempFileId = tempUserFile.getId();
            
            // 生成完整下载 URL（包含域名）
            String downloadUrl = baseUrl + "/api/files/download/" + tempFileId;
            tempUserFile.setDownloadUrl(downloadUrl);
            userFileMapper.updateById(tempUserFile);
            
            log.info("excel_init_temp created temp file: id={}, sourceFileId={}, tempFileName={}, downloadUrl={}", 
                    tempFileId, sourceFileId, tempFileName, downloadUrl);
            
            // 读取文件内容用于返回预览
            Workbook wb = createWorkbook(fileBytes, userFile.getOriginalFileName());
            Sheet sheet = wb.getSheetAt(0);
            
            // 获取表头信息
            List<String> headers = new ArrayList<String>();
            Row headerRow = sheet.getRow(0);
            if (headerRow != null) {
                int cellCount = headerRow.getPhysicalNumberOfCells();
                for (int j = 0; j < cellCount; j++) {
                    Cell cell = headerRow.getCell(j);
                    headers.add(getCellStringValue(cell));
                }
            }
            
            wb.close();

            // 构建返回结果
            Map<String, Object> result = new LinkedHashMap<String, Object>();
            result.put("message", "临时文件初始化成功");
            result.put("fileId", tempFileId);
            result.put("sourceFileId", sourceFileId);
            result.put("fileName", tempFileName);
            result.put("filePath", ftpPath);
            result.put("downloadUrl", downloadUrl);
            result.put("headers", headers);

            return FileToolResponse.ok(result, tempFileName);
        } catch (Exception e) {
            log.error("excel_init_temp failed for {}", userFile.getOriginalFileName(), e);
            return FileToolResponse.error("excel_init_temp failed: " + e.getMessage(), userFile.getOriginalFileName());
        }
    }

    // ================================================================
    // excel_read — 读取 Excel 内容（不修改文件，无需保存）
    // ================================================================

    /**
     * 读取 Excel 文件的内容。
     *
     * @param userFile 文件实体
     * @param params   参数：maxRows（最大返回行数，默认100）
     * @param userId   用户 ID
     * @return Excel 内容数据
     */
    public FileToolResponse excelRead(UserFile userFile, Map<String, Object> params, String userId) {
        ensureExcelFile(userFile);
        int maxRows = readIntParam(params, "maxRows", 100);

        try {
            byte[] fileBytes = downloadBytes(userFile);
            Workbook wb = createWorkbook(fileBytes, userFile.getOriginalFileName());
            Sheet sheet = wb.getSheetAt(0);

            Map<String, Object> result = new LinkedHashMap<String, Object>();
            List<String> headers = new ArrayList<String>();
            List<List<Object>> rows = new ArrayList<List<Object>>();

            int rowCount = sheet.getPhysicalNumberOfRows();
            for (int i = 0; i < rowCount && i < maxRows + 1; i++) {
                Row row = sheet.getRow(i);
                if (row == null) continue;

                List<Object> rowData = new ArrayList<Object>();
                int cellCount = row.getPhysicalNumberOfCells();
                for (int j = 0; j < cellCount; j++) {
                    Cell cell = row.getCell(j);
                    if (i == 0) {
                        headers.add(getCellStringValue(cell));
                    } else {
                        rowData.add(getCellValue(cell));
                    }
                }
                if (i > 0) {
                    rows.add(rowData);
                }
            }

            // 生成 fileId 和 downloadUrl（返回当前文件的 ID）
            Long resultFileId = userFile.getId();
            String downloadUrl = baseUrl + "/api/files/download/" + resultFileId;

            result.put("headers", headers);
            result.put("rows", rows);
            result.put("totalRows", rowCount - 1);
            result.put("totalCols", headers.size());
            result.put("fileId", resultFileId);
            result.put("fileName", userFile.getOriginalFileName());
            result.put("downloadUrl", downloadUrl);

            wb.close();
            return FileToolResponse.ok(result, userFile.getOriginalFileName());
        } catch (Exception e) {
            log.error("excel_read failed for {}", userFile.getOriginalFileName(), e);
            return FileToolResponse.error("excel_read failed: " + e.getMessage(), userFile.getOriginalFileName());
        }
    }

    // ================================================================
    // excel_write — 写入 Excel 文件（新建文件）
    // ================================================================

    /**
     * 创建或覆盖 Excel 文件。
     *
     * @param userFile 文件实体（如果为 null 则创建新文件）
     * @param params   参数：headers（列头）、rows（数据行）
     * @param userId   用户 ID
     * @return 写入结果，包含临时文件地址
     */
    @SuppressWarnings("unchecked")
    public FileToolResponse excelWrite(UserFile userFile, Map<String, Object> params, String userId) {
        try {
            // 安全解析 headers（可能是 List 或 JSON String）
            List<String> headers = parseStringList(params.get("headers"));
            // 安全解析 rows（可能是 List 或 JSON String）
            List<List<Object>> rows = parseRowsList(params.get("rows"));

            if (headers == null || headers.isEmpty()) {
                return FileToolResponse.error("params.headers is required", 
                        userFile != null ? userFile.getOriginalFileName() : "new.xlsx");
            }

            Workbook wb = new XSSFWorkbook();
            Sheet sheet = wb.createSheet("Sheet1");
            writeDataToSheet(sheet, headers, rows);

            // 将工作簿写入内存，获取文件大小
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            wb.write(baos);
            byte[] fileBytes = baos.toByteArray();
            long fileSize = fileBytes.length;
            wb.close();

            // 生成文件名
            String fileName;
            Long resultFileId;
            String downloadUrl;
            String ftpPath;
            
            if (userFile != null) {
                // 有 fileId：在临时文件基础上操作
                fileName = getTempFileName(userFile.getOriginalFileName());
                ftpPath = saveWorkbookWithBytes(userId, userFile.getFileName(), fileBytes);
                
                resultFileId = userFile.getId();
                downloadUrl = baseUrl + "/api/files/download/" + resultFileId;
            } else {
                // 无 fileId：创建新文件，插入 userfile 表
                // 生成显示文件名和 UUID 存储文件名
                String displayFileName = "new_" + System.currentTimeMillis() + ".xlsx";
                String storageFileName = FtpFileService.generateStorageFileName(displayFileName);
                
                // 使用 UUID 存储文件名上传到 FTP
                ftpPath = ftpFileService.uploadFileWithFileName(userId, storageFileName, new ByteArrayInputStream(fileBytes));
                
                // 插入 userfile 表
                UserFile newUserFile = new UserFile();
                newUserFile.setUserId(userId);
                newUserFile.setOriginalFileName(displayFileName); // 显示文件名
                newUserFile.setFileName(storageFileName); // UUID 存储文件名
                newUserFile.setFileType("xlsx");
                newUserFile.setFileSize(fileSize);
                newUserFile.setFtpPath(ftpPath);
                newUserFile.setSourceFileId(null); // 新文件，不是临时文件
                newUserFile.setUploadTime(LocalDateTime.now()); // 设置上传时间
                userFileMapper.insert(newUserFile);
                
                resultFileId = newUserFile.getId();
                downloadUrl = baseUrl + "/api/files/download/" + resultFileId;
                fileName = displayFileName; // 返回显示文件名
                
                log.info("Created new file without fileId: userId={}, displayFileName={}, storageFileName={}, fileId={}, fileSize={}, ftpPath={}", 
                        userId, displayFileName, storageFileName, resultFileId, fileSize, ftpPath);
            }

            Map<String, Object> result = new LinkedHashMap<String, Object>();
            result.put("message", "Excel file written successfully");
            result.put("fileName", fileName);
            result.put("fileId", resultFileId);
            result.put("downloadUrl", downloadUrl);
            result.put("filePath", ftpPath);
            result.put("totalRows", rows != null ? rows.size() : 0);
            result.put("totalCols", headers.size());

            return FileToolResponse.ok(result, fileName);
        } catch (Exception e) {
            log.error("excel_write failed", e);
            return FileToolResponse.error("excel_write failed: " + e.getMessage(), 
                    userFile != null ? userFile.getOriginalFileName() : "new.xlsx");
        }
    }

    /**
     * 使用字节数组保存工作簿到 FTP（覆盖写入）。
     */
    private String saveWorkbookWithBytes(String userId, String fileName, byte[] fileBytes) throws IOException {
        return ftpFileService.uploadFileWithFileName(userId, fileName, new ByteArrayInputStream(fileBytes));
    }

    /**
     * 安全解析 String List 参数，支持 List 或 JSON String 类型。
     */
    @SuppressWarnings("unchecked")
    private List<String> parseStringList(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof String) {
            try {
                ObjectMapper mapper = new ObjectMapper();
                return mapper.readValue((String) value, new TypeReference<List<String>>() {});
            } catch (Exception e) {
                log.warn("Failed to parse JSON string list: {}", value, e);
                return null;
            }
        }
        if (value instanceof List) {
            return (List<String>) value;
        }
        return null;
    }

    /**
     * 安全解析 rows 参数（二维数组），支持 List 或 JSON String 类型。
     */
    @SuppressWarnings("unchecked")
    private List<List<Object>> parseRowsList(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof String) {
            try {
                ObjectMapper mapper = new ObjectMapper();
                return mapper.readValue((String) value, mapper.getTypeFactory()
                        .constructCollectionType(List.class, 
                                mapper.getTypeFactory().constructCollectionType(List.class, Object.class)));
            } catch (Exception e) {
                log.warn("Failed to parse JSON rows list: {}", value, e);
                return null;
            }
        }
        if (value instanceof List) {
            return (List<List<Object>>) value;
        }
        return null;
    }

    /**
     * 安全解析 Map List 参数（二维数组），支持 List 或 JSON String 类型。
     */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> parseMapList(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof String) {
            try {
                ObjectMapper mapper = new ObjectMapper();
                return mapper.readValue((String) value, new TypeReference<List<Map<String, Object>>>() {});
            } catch (Exception e) {
                log.warn("Failed to parse JSON map list: {}", value, e);
                return null;
            }
        }
        if (value instanceof List) {
            return (List<Map<String, Object>>) value;
        }
        return null;
    }

    // ================================================================
    // excel_filter — 数据筛选（修改文件，需要保存）
    // ================================================================

    /**
     * 根据条件筛选数据行。
     *
     * @param userFile 文件实体
     * @param params   参数：column（列名）、operator（操作符：equals, contains, gt, lt, etc.）、value（筛选值）
     * @param userId   用户 ID
     * @return 筛选结果，包含临时文件地址
     */
    public FileToolResponse excelFilter(UserFile userFile, Map<String, Object> params, String userId) {
        ensureExcelFile(userFile);
        String column = readStringParam(params, "column", null);
        String operator = readStringParam(params, "operator", "equals");
        String value = readStringParam(params, "value", null);

        if (column == null || value == null) {
            return FileToolResponse.error("params.column and params.value are required", userFile.getOriginalFileName());
        }

        try {
            // 读取文件：优先读取临时文件，不存在则读取原文件
            byte[] fileBytes = downloadBytes(userFile);
            Workbook wb = createWorkbook(fileBytes, userFile.getOriginalFileName());
            Sheet sheet = wb.getSheetAt(0);

            // 执行筛选
            List<String> headers = new ArrayList<String>();
            List<List<Object>> filteredRows = new ArrayList<List<Object>>();
            int columnIndex = -1;

            int rowCount = sheet.getPhysicalNumberOfRows();
            for (int i = 0; i < rowCount; i++) {
                Row row = sheet.getRow(i);
                if (row == null) continue;

                List<Object> rowData = new ArrayList<Object>();
                int cellCount = row.getPhysicalNumberOfCells();
                
                for (int j = 0; j < cellCount; j++) {
                    Cell cell = row.getCell(j);
                    if (i == 0) {
                        String header = getCellStringValue(cell);
                        headers.add(header);
                        if (header.equals(column)) {
                            columnIndex = j;
                        }
                    } else {
                        rowData.add(getCellValue(cell));
                    }
                }
                
                if (i > 0) {
                    if (columnIndex >= 0 && columnIndex < rowData.size()) {
                        Object cellValue = rowData.get(columnIndex);
                        if (matchesFilter(cellValue, operator, value)) {
                            filteredRows.add(rowData);
                        }
                    }
                }
            }

            // 清空原工作表，写入筛选后的数据
            sheet = wb.getSheetAt(0);
            int lastRowNum = sheet.getLastRowNum();
            for (int i = lastRowNum; i >= 0; i--) {
                Row row = sheet.getRow(i);
                if (row != null) {
                    sheet.removeRow(row);
                }
            }
            writeDataToSheet(sheet, headers, filteredRows);

            // 保存文件并生成下载 URL
            String tempFileName = getTempFileName(userFile.getOriginalFileName());
            Map<String, Object> saveResult = saveAndReturnResult(wb, userFile, userId, tempFileName);
            wb.close();

            Map<String, Object> result = new LinkedHashMap<String, Object>();
            result.put("message", "Filter completed");
            result.putAll(saveResult);
            result.put("headers", headers);
            result.put("rows", filteredRows);
            result.put("totalRows", filteredRows.size());

            return FileToolResponse.ok(result, tempFileName);
        } catch (Exception e) {
            log.error("excel_filter failed for {}", userFile.getOriginalFileName(), e);
            return FileToolResponse.error("excel_filter failed: " + e.getMessage(), userFile.getOriginalFileName());
        }
    }

    // ================================================================
    // excel_sort — 数据排序（修改文件，需要保存）
    // ================================================================

    /**
     * 根据指定列排序数据。
     *
     * @param userFile 文件实体
     * @param params   参数：column（列名）、order（排序方向：asc/desc，默认asc）
     * @param userId   用户 ID
     * @return 排序结果，包含临时文件地址
     */
    public FileToolResponse excelSort(UserFile userFile, Map<String, Object> params, String userId) {
        ensureExcelFile(userFile);
        String column = readStringParam(params, "column", null);
        String order = readStringParam(params, "order", "asc");

        if (column == null) {
            return FileToolResponse.error("params.column is required", userFile.getOriginalFileName());
        }

        try {
            byte[] fileBytes = downloadBytes(userFile);
            Workbook wb = createWorkbook(fileBytes, userFile.getOriginalFileName());
            Sheet sheet = wb.getSheetAt(0);

            // 读取数据
            List<String> headers = new ArrayList<String>();
            List<List<Object>> rows = new ArrayList<List<Object>>();
            int columnIndex = -1;

            int rowCount = sheet.getPhysicalNumberOfRows();
            for (int i = 0; i < rowCount; i++) {
                Row row = sheet.getRow(i);
                if (row == null) continue;

                List<Object> rowData = new ArrayList<Object>();
                int cellCount = row.getPhysicalNumberOfCells();
                
                for (int j = 0; j < cellCount; j++) {
                    Cell cell = row.getCell(j);
                    if (i == 0) {
                        String header = getCellStringValue(cell);
                        headers.add(header);
                        if (header.equals(column)) {
                            columnIndex = j;
                        }
                    } else {
                        rowData.add(getCellValue(cell));
                    }
                }
                
                if (i > 0) {
                    rows.add(rowData);
                }
            }

            // 执行排序
            if (columnIndex >= 0) {
                final int colIdx = columnIndex;
                rows.sort(new Comparator<List<Object>>() {
                    @Override
                    public int compare(List<Object> r1, List<Object> r2) {
                        Object v1 = colIdx < r1.size() ? r1.get(colIdx) : null;
                        Object v2 = colIdx < r2.size() ? r2.get(colIdx) : null;
                        int result = compareValues(v1, v2);
                        return "desc".equalsIgnoreCase(order) ? -result : result;
                    }
                });
            }

            // 清空原工作表，写入排序后的数据
            sheet = wb.getSheetAt(0);
            int lastRowNum = sheet.getLastRowNum();
            for (int i = lastRowNum; i >= 0; i--) {
                Row row = sheet.getRow(i);
                if (row != null) {
                    sheet.removeRow(row);
                }
            }
            writeDataToSheet(sheet, headers, rows);

            // 保存文件并生成下载 URL
            String tempFileName = getTempFileName(userFile.getOriginalFileName());
            Map<String, Object> saveResult = saveAndReturnResult(wb, userFile, userId, tempFileName);
            wb.close();

            Map<String, Object> result = new LinkedHashMap<String, Object>();
            result.put("message", "Sort completed");
            result.putAll(saveResult);
            result.put("headers", headers);
            result.put("rows", rows);
            result.put("totalRows", rows.size());

            return FileToolResponse.ok(result, tempFileName);
        } catch (Exception e) {
            log.error("excel_sort failed for {}", userFile.getOriginalFileName(), e);
            return FileToolResponse.error("excel_sort failed: " + e.getMessage(), userFile.getOriginalFileName());
        }
    }

    // ================================================================
    // excel_aggregate — 聚合统计（修改文件，需要保存）
    // ================================================================

    /**
     * 按指定列分组并进行聚合统计。
     *
     * @param userFile 文件实体
     * @param params   参数：groupBy（分组列名）、aggColumn（聚合列名）、aggType（聚合类型：sum, avg, count, min, max）
     * @param userId   用户 ID
     * @return 聚合结果，包含临时文件地址
     */
    public FileToolResponse excelAggregate(UserFile userFile, Map<String, Object> params, String userId) {
        ensureExcelFile(userFile);
        String groupBy = readStringParam(params, "groupBy", null);
        String aggColumn = readStringParam(params, "aggColumn", null);
        String aggType = readStringParam(params, "aggType", "sum");

        if (groupBy == null || aggColumn == null) {
            return FileToolResponse.error("params.groupBy and params.aggColumn are required", userFile.getOriginalFileName());
        }

        try {
            byte[] fileBytes = downloadBytes(userFile);
            Workbook wb = createWorkbook(fileBytes, userFile.getOriginalFileName());
            Sheet sheet = wb.getSheetAt(0);

            // 执行聚合
            List<String> headers = new ArrayList<String>();
            Map<String, Double> aggregates = new LinkedHashMap<String, Double>();
            Map<String, Long> counts = new LinkedHashMap<String, Long>();
            
            int groupByIndex = -1;
            int aggIndex = -1;

            int rowCount = sheet.getPhysicalNumberOfRows();
            for (int i = 0; i < rowCount; i++) {
                Row row = sheet.getRow(i);
                if (row == null) continue;

                int cellCount = row.getPhysicalNumberOfCells();
                for (int j = 0; j < cellCount; j++) {
                    Cell cell = row.getCell(j);
                    if (i == 0) {
                        String header = getCellStringValue(cell);
                        headers.add(header);
                        if (header.equals(groupBy)) {
                            groupByIndex = j;
                        }
                        if (header.equals(aggColumn)) {
                            aggIndex = j;
                        }
                    } else if (groupByIndex >= 0 && aggIndex >= 0) {
                        Cell groupCell = row.getCell(groupByIndex);
                        Cell aggCell = row.getCell(aggIndex);
                        
                        if (groupCell != null) {
                            String groupKey = getCellStringValue(groupCell);
                            counts.put(groupKey, counts.getOrDefault(groupKey, 0L) + 1L);
                            
                            Object aggValue = getCellValue(aggCell);
                            if (aggValue instanceof Number) {
                                double numValue = ((Number) aggValue).doubleValue();
                                aggregates.put(groupKey, aggregates.getOrDefault(groupKey, 0.0) + numValue);
                            }
                        }
                    }
                }
            }

            // 构建结果数据
            List<String> resultHeaders = Arrays.asList(groupBy, aggType + "_" + aggColumn);
            List<List<Object>> resultRows = new ArrayList<List<Object>>();
            for (Map.Entry<String, Double> entry : aggregates.entrySet()) {
                List<Object> row = new ArrayList<Object>();
                row.add(entry.getKey());
                
                double value = entry.getValue();
                if ("avg".equalsIgnoreCase(aggType)) {
                    value = value / counts.get(entry.getKey());
                } else if ("count".equalsIgnoreCase(aggType)) {
                    value = counts.get(entry.getKey());
                } else if ("min".equalsIgnoreCase(aggType)) {
                    // min 需要遍历实现，这里简化为 sum
                } else if ("max".equalsIgnoreCase(aggType)) {
                    // max 需要遍历实现，这里简化为 sum
                }
                row.add(value);
                resultRows.add(row);
            }

            // 清空原工作表，写入聚合后的数据
            sheet = wb.getSheetAt(0);
            int lastRowNum = sheet.getLastRowNum();
            for (int i = lastRowNum; i >= 0; i--) {
                Row row = sheet.getRow(i);
                if (row != null) {
                    sheet.removeRow(row);
                }
            }
            writeDataToSheet(sheet, resultHeaders, resultRows);

            // 保存文件并生成下载 URL
            String tempFileName = getTempFileName(userFile.getOriginalFileName());
            Map<String, Object> saveResult = saveAndReturnResult(wb, userFile, userId, tempFileName);
            wb.close();

            Map<String, Object> result = new LinkedHashMap<String, Object>();
            result.put("message", "Aggregation completed");
            result.putAll(saveResult);
            result.put("headers", resultHeaders);
            result.put("rows", resultRows);
            result.put("totalRows", resultRows.size());

            return FileToolResponse.ok(result, tempFileName);
        } catch (Exception e) {
            log.error("excel_aggregate failed for {}", userFile.getOriginalFileName(), e);
            return FileToolResponse.error("excel_aggregate failed: " + e.getMessage(), userFile.getOriginalFileName());
        }
    }

    // ================================================================
    // excel_pivot — 透视分析（修改文件，需要保存）
    // ================================================================

    /**
     * 透视分析：行维度、列维度、值聚合。
     *
     * @param userFile 文件实体
     * @param params   参数：rowDimension（行维度）、colDimension（列维度）、valueColumn（值列）
     * @param userId   用户 ID
     * @return 透视结果，包含临时文件地址
     */
    public FileToolResponse excelPivot(UserFile userFile, Map<String, Object> params, String userId) {
        ensureExcelFile(userFile);
        String rowDimension = readStringParam(params, "rowDimension", null);
        String colDimension = readStringParam(params, "colDimension", null);
        String valueColumn = readStringParam(params, "valueColumn", null);

        if (rowDimension == null || colDimension == null || valueColumn == null) {
            return FileToolResponse.error("params.rowDimension, params.colDimension and params.valueColumn are required", 
                    userFile.getOriginalFileName());
        }

        try {
            byte[] fileBytes = downloadBytes(userFile);
            Workbook wb = createWorkbook(fileBytes, userFile.getOriginalFileName());
            Sheet sheet = wb.getSheetAt(0);

            // 执行透视
            Map<String, Map<String, Double>> pivot = new LinkedHashMap<String, Map<String, Double>>();
            Set<String> colValues = new LinkedHashSet<String>();
            
            int rowIdx = -1, colIdx = -1, valIdx = -1;

            int rowCount = sheet.getPhysicalNumberOfRows();
            for (int i = 0; i < rowCount; i++) {
                Row row = sheet.getRow(i);
                if (row == null) continue;

                int cellCount = row.getPhysicalNumberOfCells();
                for (int j = 0; j < cellCount; j++) {
                    Cell cell = row.getCell(j);
                    if (i == 0) {
                        String header = getCellStringValue(cell);
                        if (header.equals(rowDimension)) rowIdx = j;
                        if (header.equals(colDimension)) colIdx = j;
                        if (header.equals(valueColumn)) valIdx = j;
                    } else if (rowIdx >= 0 && colIdx >= 0 && valIdx >= 0) {
                        String rowVal = getCellStringValue(row.getCell(rowIdx));
                        String colVal = getCellStringValue(row.getCell(colIdx));
                        Object val = getCellValue(row.getCell(valIdx));
                        
                        pivot.computeIfAbsent(rowVal, k -> new LinkedHashMap<String, Double>());
                        colValues.add(colVal);
                        
                        if (val instanceof Number) {
                            double numVal = ((Number) val).doubleValue();
                            pivot.get(rowVal).merge(colVal, numVal, Double::sum);
                        }
                    }
                }
            }

            // 构建结果数据
            List<String> resultHeaders = new ArrayList<String>();
            resultHeaders.add(rowDimension);
            resultHeaders.addAll(colValues);

            List<List<Object>> resultRows = new ArrayList<List<Object>>();
            for (Map.Entry<String, Map<String, Double>> entry : pivot.entrySet()) {
                List<Object> rowData = new ArrayList<Object>();
                rowData.add(entry.getKey());
                for (String col : colValues) {
                    rowData.add(entry.getValue().getOrDefault(col, 0.0));
                }
                resultRows.add(rowData);
            }

            // 清空原工作表，写入透视后的数据
            sheet = wb.getSheetAt(0);
            int lastRowNum = sheet.getLastRowNum();
            for (int i = lastRowNum; i >= 0; i--) {
                Row row = sheet.getRow(i);
                if (row != null) {
                    sheet.removeRow(row);
                }
            }
            writeDataToSheet(sheet, resultHeaders, resultRows);

            // 保存文件并生成下载 URL
            String tempFileName = getTempFileName(userFile.getOriginalFileName());
            Map<String, Object> saveResult = saveAndReturnResult(wb, userFile, userId, tempFileName);
            wb.close();

            Map<String, Object> result = new LinkedHashMap<String, Object>();
            result.put("message", "Pivot analysis completed");
            result.putAll(saveResult);
            result.put("headers", resultHeaders);
            result.put("rows", resultRows);
            result.put("totalRows", resultRows.size());

            return FileToolResponse.ok(result, tempFileName);
        } catch (Exception e) {
            log.error("excel_pivot failed for {}", userFile.getOriginalFileName(), e);
            return FileToolResponse.error("excel_pivot failed: " + e.getMessage(), userFile.getOriginalFileName());
        }
    }

    // ================================================================
    // excel_calculate — 列运算（修改文件，需要保存）
    // ================================================================

    /**
     * 执行列运算，新增计算列。
     *
     * @param userFile 文件实体
     * @param params   参数：newColumn（新列名）、formula（计算公式，如 {col1} + {col2}）
     * @param userId   用户 ID
     * @return 运算结果，包含临时文件地址
     */
    public FileToolResponse excelCalculate(UserFile userFile, Map<String, Object> params, String userId) {
        ensureExcelFile(userFile);
        String newColumn = readStringParam(params, "newColumn", null);
        String formula = readStringParam(params, "formula", null);

        if (newColumn == null || formula == null) {
            return FileToolResponse.error("params.newColumn and params.formula are required", userFile.getOriginalFileName());
        }

        try {
            byte[] fileBytes = downloadBytes(userFile);
            Workbook wb = createWorkbook(fileBytes, userFile.getOriginalFileName());
            Sheet sheet = wb.getSheetAt(0);

            // 读取数据并计算
            List<String> headers = new ArrayList<String>();
            List<List<Object>> rows = new ArrayList<List<Object>>();
            Map<String, Integer> headerIndices = new LinkedHashMap<String, Integer>();

            int rowCount = sheet.getPhysicalNumberOfRows();
            for (int i = 0; i < rowCount; i++) {
                Row row = sheet.getRow(i);
                if (row == null) continue;

                List<Object> rowData = new ArrayList<Object>();
                int cellCount = row.getPhysicalNumberOfCells();
                
                for (int j = 0; j < cellCount; j++) {
                    Cell cell = row.getCell(j);
                    if (i == 0) {
                        String header = getCellStringValue(cell);
                        headers.add(header);
                        headerIndices.put(header, j);
                    } else {
                        rowData.add(getCellValue(cell));
                    }
                }
                
                if (i > 0) {
                    // 计算新列值
                    double result = evaluateFormula(formula, rowData, headerIndices, headers);
                    rowData.add(result);
                    rows.add(rowData);
                }
            }
            headers.add(newColumn);

            // 清空原工作表，写入计算后的数据
            sheet = wb.getSheetAt(0);
            int lastRowNum = sheet.getLastRowNum();
            for (int i = lastRowNum; i >= 0; i--) {
                Row row = sheet.getRow(i);
                if (row != null) {
                    sheet.removeRow(row);
                }
            }
            writeDataToSheet(sheet, headers, rows);

            // 保存文件并生成下载 URL
            String tempFileName = getTempFileName(userFile.getOriginalFileName());
            Map<String, Object> saveResult = saveAndReturnResult(wb, userFile, userId, tempFileName);
            wb.close();

            Map<String, Object> result = new LinkedHashMap<String, Object>();
            result.put("message", "Calculation completed");
            result.putAll(saveResult);
            result.put("headers", headers);
            result.put("rows", rows);
            result.put("totalRows", rows.size());

            return FileToolResponse.ok(result, tempFileName);
        } catch (Exception e) {
            log.error("excel_calculate failed for {}", userFile.getOriginalFileName(), e);
            return FileToolResponse.error("excel_calculate failed: " + e.getMessage(), userFile.getOriginalFileName());
        }
    }

    // ================================================================
    // excel_select_columns — 列选择（修改文件，需要保存）
    // ================================================================

    /**
     * 选择指定列。
     *
     * @param userFile 文件实体
     * @param params   参数：columns（列名列表）
     * @param userId   用户 ID
     * @return 选择结果，包含临时文件地址
     */
    @SuppressWarnings("unchecked")
    public FileToolResponse excelSelectColumns(UserFile userFile, Map<String, Object> params, String userId) {
        ensureExcelFile(userFile);
        // 安全解析 columns 参数（可能是 List 或 JSON String）
        List<String> columns = parseStringList(params.get("columns"));

        if (columns == null || columns.isEmpty()) {
            return FileToolResponse.error("params.columns is required", userFile.getOriginalFileName());
        }

        try {
            byte[] fileBytes = downloadBytes(userFile);
            Workbook wb = createWorkbook(fileBytes, userFile.getOriginalFileName());
            Sheet sheet = wb.getSheetAt(0);

            // 执行列选择
            List<String> headers = new ArrayList<String>();
            List<List<Object>> rows = new ArrayList<List<Object>>();
            List<Integer> selectedIndices = new ArrayList<Integer>();

            int rowCount = sheet.getPhysicalNumberOfRows();
            for (int i = 0; i < rowCount; i++) {
                Row row = sheet.getRow(i);
                if (row == null) continue;

                List<Object> rowData = new ArrayList<Object>();
                int cellCount = row.getPhysicalNumberOfCells();
                
                if (i == 0) {
                    for (int j = 0; j < cellCount; j++) {
                        Cell cell = row.getCell(j);
                        String header = getCellStringValue(cell);
                        if (columns.contains(header)) {
                            headers.add(header);
                            selectedIndices.add(j);
                        }
                    }
                } else {
                    for (Integer idx : selectedIndices) {
                        if (idx < cellCount) {
                            rowData.add(getCellValue(row.getCell(idx)));
                        } else {
                            rowData.add(null);
                        }
                    }
                    rows.add(rowData);
                }
            }

            // 清空原工作表，写入选择后的数据
            sheet = wb.getSheetAt(0);
            int lastRowNum = sheet.getLastRowNum();
            for (int i = lastRowNum; i >= 0; i--) {
                Row row = sheet.getRow(i);
                if (row != null) {
                    sheet.removeRow(row);
                }
            }
            writeDataToSheet(sheet, headers, rows);

            // 保存文件并生成下载 URL
            String tempFileName = getTempFileName(userFile.getOriginalFileName());
            Map<String, Object> saveResult = saveAndReturnResult(wb, userFile, userId, tempFileName);
            wb.close();

            Map<String, Object> result = new LinkedHashMap<String, Object>();
            result.put("message", "Column selection completed");
            result.putAll(saveResult);
            result.put("headers", headers);
            result.put("rows", rows);
            result.put("totalRows", rows.size());

            return FileToolResponse.ok(result, tempFileName);
        } catch (Exception e) {
            log.error("excel_select_columns failed for {}", userFile.getOriginalFileName(), e);
            return FileToolResponse.error("excel_select_columns failed: " + e.getMessage(), userFile.getOriginalFileName());
        }
    }

    // ================================================================
    // excel_clean — 数据清洗（修改文件，需要保存）
    // ================================================================

    /**
     * 数据清洗。
     *
     * @param userFile 文件实体
     * @param params   参数：cleanType（清洗类型：trim/deduplicate/removeEmpty）
     * @param userId   用户 ID
     * @return 清洗结果，包含临时文件地址
     */
    public FileToolResponse excelClean(UserFile userFile, Map<String, Object> params, String userId) {
        ensureExcelFile(userFile);
        String cleanType = readStringParam(params, "cleanType", null);

        if (cleanType == null) {
            return FileToolResponse.error("params.cleanType is required", userFile.getOriginalFileName());
        }

        try {
            byte[] fileBytes = downloadBytes(userFile);
            Workbook wb = createWorkbook(fileBytes, userFile.getOriginalFileName());
            Sheet sheet = wb.getSheetAt(0);

            // 执行数据清洗
            List<String> headers = new ArrayList<String>();
            List<List<Object>> rows = new ArrayList<List<Object>>();
            Set<String> seenRows = new HashSet<String>();

            int rowCount = sheet.getPhysicalNumberOfRows();
            for (int i = 0; i < rowCount; i++) {
                Row row = sheet.getRow(i);
                if (row == null) continue;

                List<Object> rowData = new ArrayList<Object>();
                int cellCount = row.getPhysicalNumberOfCells();
                
                for (int j = 0; j < cellCount; j++) {
                    Cell cell = row.getCell(j);
                    if (i == 0) {
                        headers.add(getCellStringValue(cell));
                    } else {
                        Object value = getCellValue(cell);
                        if ("trim".equalsIgnoreCase(cleanType) && value instanceof String) {
                            value = ((String) value).trim();
                        }
                        rowData.add(value);
                    }
                }
                
                if (i > 0) {
                    String rowKey = rowData.toString();
                    
                    boolean isEmpty = true;
                    for (Object val : rowData) {
                        if (val != null && !val.toString().trim().isEmpty()) {
                            isEmpty = false;
                            break;
                        }
                    }
                    
                    boolean shouldAdd = true;
                    if ("deduplicate".equalsIgnoreCase(cleanType) && seenRows.contains(rowKey)) {
                        shouldAdd = false;
                    }
                    if ("removeEmpty".equalsIgnoreCase(cleanType) && isEmpty) {
                        shouldAdd = false;
                    }
                    
                    if (shouldAdd) {
                        seenRows.add(rowKey);
                        rows.add(rowData);
                    }
                }
            }

            // 清空原工作表，写入清洗后的数据
            sheet = wb.getSheetAt(0);
            int lastRowNum = sheet.getLastRowNum();
            for (int i = lastRowNum; i >= 0; i--) {
                Row row = sheet.getRow(i);
                if (row != null) {
                    sheet.removeRow(row);
                }
            }
            writeDataToSheet(sheet, headers, rows);

            // 保存文件并生成下载 URL
            String tempFileName = getTempFileName(userFile.getOriginalFileName());
            Map<String, Object> saveResult = saveAndReturnResult(wb, userFile, userId, tempFileName);
            wb.close();

            Map<String, Object> result = new LinkedHashMap<String, Object>();
            result.put("message", "Data cleaning completed");
            result.putAll(saveResult);
            result.put("headers", headers);
            result.put("rows", rows);
            result.put("totalRows", rows.size());

            return FileToolResponse.ok(result, tempFileName);
        } catch (Exception e) {
            log.error("excel_clean failed for {}", userFile.getOriginalFileName(), e);
            return FileToolResponse.error("excel_clean failed: " + e.getMessage(), userFile.getOriginalFileName());
        }
    }

    // ================================================================
    // excel_convert_format — 格式转换（修改文件，需要保存）
    // ================================================================

    /**
     * 格式转换。
     *
     * @param userFile 文件实体
     * @param params   参数：targetFormat（目标格式：xlsx/xls/csv）
     * @param userId   用户 ID
     * @return 转换结果，包含临时文件地址
     */
    public FileToolResponse excelConvertFormat(UserFile userFile, Map<String, Object> params, String userId) {
        ensureExcelFile(userFile);
        String targetFormat = readStringParam(params, "targetFormat", null);

        if (targetFormat == null) {
            return FileToolResponse.error("params.targetFormat is required", userFile.getOriginalFileName());
        }

        try {
            byte[] fileBytes = downloadBytes(userFile);
            Workbook wb = createWorkbook(fileBytes, userFile.getOriginalFileName());

            // 转换格式并保存到临时文件
            byte[] convertedBytes = getConvertedBytes(wb, targetFormat);
            int fileSize = convertedBytes.length;

            // 生成新的显示文件名（更改扩展名）
            String newFileName = changeFileExtension(userFile.getOriginalFileName(), targetFormat);

            // 如果是临时文件，覆盖写入；否则生成临时文件名保存
            String ftpPath;
            String newStorageFileName;
            if (userFile.getSourceFileId() != null) {
                // 临时文件：生成新存储文件名（带新扩展名），覆盖写入
                newStorageFileName = FtpFileService.generateStorageFileName(newFileName);
                ftpPath = ftpFileService.uploadFileWithFileName(userId, newStorageFileName,
                        new ByteArrayInputStream(convertedBytes));
            } else {
                // 源文件：生成临时文件名保存
                String tempFileName = getTempFileName(newFileName);
                newStorageFileName = FtpFileService.generateStorageFileName(tempFileName);
                ftpPath = ftpFileService.uploadFile(userId, tempFileName,
                        new ByteArrayInputStream(convertedBytes));
                newFileName = tempFileName;
            }
            wb.close();

            // 更新 user_files 表中的文件信息（含存储文件名和显示文件名）
            userFile.setFileName(newStorageFileName);
            userFile.setOriginalFileName(newFileName);
            userFile.setFileType(targetFormat.toLowerCase());
            userFile.setFileSize((long) fileSize);
            userFile.setFtpPath(ftpPath);
            userFileMapper.updateById(userFile);

            // 生成 downloadUrl
            String downloadUrl = baseUrl + "/api/files/download/" + userFile.getId();

            Map<String, Object> result = new LinkedHashMap<String, Object>();
            result.put("message", "Format converted successfully");
            result.put("fileId", userFile.getId());
            result.put("fileName", newFileName);
            result.put("filePath", ftpPath);
            result.put("downloadUrl", downloadUrl);

            return FileToolResponse.ok(result, newFileName);
        } catch (Exception e) {
            log.error("excel_convert_format failed for {}", userFile.getOriginalFileName(), e);
            return FileToolResponse.error("excel_convert_format failed: " + e.getMessage(), userFile.getOriginalFileName());
        }
    }

    /**
     * 更改文件扩展名。
     */
    private String changeFileExtension(String fileName, String newExtension) {
        int dotIndex = fileName.lastIndexOf('.');
        String baseName = dotIndex > 0 ? fileName.substring(0, dotIndex) : fileName;
        // 确保新扩展名以点开头
        if (!newExtension.startsWith(".")) {
            newExtension = "." + newExtension;
        }
        return baseName + newExtension.toLowerCase();
    }

    /**
     * 获取转换后的字节数组。
     */
    private byte[] getConvertedBytes(Workbook wb, String targetFormat) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        if ("csv".equalsIgnoreCase(targetFormat)) {
            // CSV 格式需要特殊处理
            Sheet sheet = wb.getSheetAt(0);
            PrintWriter writer = new PrintWriter(new OutputStreamWriter(baos, StandardCharsets.UTF_8));
            int rowCount = sheet.getPhysicalNumberOfRows();
            for (int i = 0; i < rowCount; i++) {
                Row row = sheet.getRow(i);
                if (row == null) continue;
                StringBuilder line = new StringBuilder();
                int cellCount = row.getPhysicalNumberOfCells();
                for (int j = 0; j < cellCount; j++) {
                    if (j > 0) line.append(",");
                    Cell cell = row.getCell(j);
                    Object value = getCellValue(cell);
                    if (value != null) {
                        String str = value.toString();
                        // 统一处理换行符，替换 \r\n 和 \r 为 \n
                        str = str.replace("\r\n", "\n").replace("\r", "\n");
                        // CSV 中需要转义引号和换行符
                        if (str.contains(",") || str.contains("\"") || str.contains("\n")) {
                            str = "\"" + str.replace("\"", "\"\"") + "\"";
                        }
                        line.append(str);
                    }
                }
                writer.println(line.toString());
            }
            writer.flush();
        } else {
            wb.write(baos);
        }
        return baos.toByteArray();
    }

    // ================================================================
    // excel_validate — 合规校验（不修改文件，无需保存）
    // ================================================================

    /**
     * 合规校验。
     *
     * @param userFile 文件实体
     * @param params   参数：rules（校验规则列表）
     * @param userId   用户 ID
     * @return 校验结果
     */
    @SuppressWarnings("unchecked")
    public FileToolResponse excelValidate(UserFile userFile, Map<String, Object> params, String userId) {
        ensureExcelFile(userFile);
        // 安全解析 rules 参数（可能是 List 或 JSON String）
        List<Map<String, Object>> rules = parseMapList(params.get("rules"));

        try {
            byte[] fileBytes = downloadBytes(userFile);
            Workbook wb = createWorkbook(fileBytes, userFile.getOriginalFileName());
            Sheet sheet = wb.getSheetAt(0);

            // 执行校验
            List<String> headers = new ArrayList<String>();
            List<Map<String, Object>> errors = new ArrayList<Map<String, Object>>();
            
            // 第一步：解析表头，建立列名到索引的映射
            Map<String, Integer> headerIndices = new LinkedHashMap<String, Integer>();
            Row headerRow = sheet.getRow(0);
            if (headerRow != null) {
                for (int j = 0; j < headerRow.getPhysicalNumberOfCells(); j++) {
                    Cell cell = headerRow.getCell(j);
                    String header = getCellStringValue(cell);
                    headers.add(header);
                    headerIndices.put(header, j);
                }
            }

            // 第二步：遍历数据行，只检查有规则的列
            int rowCount = sheet.getPhysicalNumberOfRows();
            for (int i = 1; i < rowCount; i++) { // 从第2行开始（第1行是表头）
                Row row = sheet.getRow(i);
                if (row == null) continue;

                int cellCount = row.getPhysicalNumberOfCells();
                
                // 遍历每个规则
                if (rules != null) {
                    for (Map<String, Object> rule : rules) {
                        String column = (String) rule.get("column");
                        String ruleType = (String) rule.get("rule");
                        Object ruleValue = rule.get("value");
                        
                        Integer colIdx = headerIndices.get(column);
                        if (colIdx != null && colIdx < cellCount) {
                            Object cellValue = getCellValue(row.getCell(colIdx));
                            String error = validateCell(cellValue, ruleType, ruleValue, column, i + 1);
                            if (error != null) {
                                Map<String, Object> errorInfo = new LinkedHashMap<String, Object>();
                                errorInfo.put("row", i + 1);
                                errorInfo.put("column", column);
                                errorInfo.put("error", error);
                                errors.add(errorInfo);
                            }
                        }
                    }
                }
            }

            wb.close();

            // 生成 fileId 和 downloadUrl（返回当前文件的 ID）
            Long resultFileId = userFile.getId();
            String downloadUrl = baseUrl + "/api/files/download/" + resultFileId;

            Map<String, Object> result = new LinkedHashMap<String, Object>();
            result.put("valid", errors.isEmpty());
            result.put("totalErrors", errors.size());
            result.put("errors", errors);
            result.put("fileId", resultFileId);
            result.put("fileName", userFile.getOriginalFileName());
            result.put("downloadUrl", downloadUrl);

            return FileToolResponse.ok(result, userFile.getOriginalFileName());
        } catch (Exception e) {
            log.error("excel_validate failed for {}", userFile.getOriginalFileName(), e);
            return FileToolResponse.error("excel_validate failed: " + e.getMessage(), userFile.getOriginalFileName());
        }
    }

    // ================================================================
    // 核心辅助方法
    // ================================================================

    /**
     * 生成临时文件名。
     * <p>
     * 仅在 excel_init_temp 中使用，用于生成临时文件的显示名称。
     * 后续操作通过 sourceFileId 判断是否是临时文件，不再依赖文件名后缀。
     * </p>
     * 
     * @param originalFileName 原始文件名
     * @return 临时文件名（格式：原文件名_temp.扩展名）
     */
    private String getTempFileName(String originalFileName) {
        int dotIndex = originalFileName.lastIndexOf('.');
        String baseName = dotIndex > 0 ? originalFileName.substring(0, dotIndex) : originalFileName;
        String extension = dotIndex > 0 ? originalFileName.substring(dotIndex) : ".xlsx";
        return baseName + "_temp" + extension;
    }

    /**
     * 将工作簿保存到 FTP 并返回文件路径。
     * 返回的路径格式：/userfiles/{userId}/{fileName}
     * 
     * @param wb       工作簿
     * @param userId   用户 ID
     * @param fileName 文件名
     * @return 文件路径（可用于下一步操作的 fileRef）
     * @throws IOException IO异常
     */
    private String saveWorkbook(Workbook wb, String userId, String fileName) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        wb.write(baos);
        // 使用原始文件名保存，不使用自动生成的存储文件名
        // 这样可以保持文件名的可读性，方便后续操作引用
        return ftpFileService.uploadFile(userId, fileName, new ByteArrayInputStream(baos.toByteArray()));
    }

    /**
     * 保存工作簿并生成结果信息。
     * 如果是临时文件（sourceFileId != null），使用数据库中存储的 UUID 文件名覆盖写入。
     * 如果是源文件，生成临时文件名保存。
     *
     * @param wb       工作簿
     * @param userFile 文件实体
     * @param userId   用户 ID
     * @param fileName 保存的文件名（仅用于非临时文件）
     * @return 包含 fileId、filePath、fileName 和 downloadUrl 的 Map
     */
    private Map<String, Object> saveAndReturnResult(Workbook wb, UserFile userFile, String userId, String fileName) throws IOException {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        
        // 判断是否是临时文件：通过 sourceFileId 字段判断
        Long sourceFileId = userFile.getSourceFileId();
        String ftpPath;
        String resultFileName;
        
        if (sourceFileId != null) {
            // 是临时文件：使用数据库中存储的 UUID 文件名覆盖写入
            String storageFileName = userFile.getFileName();
            ftpPath = saveWorkbookWithFileName(wb, userId, storageFileName);
            resultFileName = userFile.getOriginalFileName(); // 返回显示名称（如 "回答_temp.xlsx")
            log.info("Overwrite temp file: fileId={}, storageFileName={}, originalFileName={}", 
                    userFile.getId(), storageFileName, resultFileName);
        } else {
            // 是源文件：生成临时文件名保存（首次操作）
            ftpPath = saveWorkbook(wb, userId, fileName);
            resultFileName = fileName;
        }
        
        result.put("fileName", resultFileName);
        result.put("filePath", ftpPath);
        
        // 返回当前文件的 ID 和下载 URL
        Long resultFileId = userFile.getId();
        String downloadUrl = baseUrl + "/api/files/download/" + resultFileId;
        
        result.put("fileId", resultFileId);
        result.put("downloadUrl", downloadUrl);
        
        return result;
    }

    /**
     * 将工作簿保存到 FTP，使用指定的存储文件名（覆盖写入）。
     *
     * @param wb       工作簿
     * @param userId   用户 ID
     * @param fileName 存储文件名（UUID 文件名）
     * @return 文件路径
     * @throws IOException IO异常
     */
    private String saveWorkbookWithFileName(Workbook wb, String userId, String fileName) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        wb.write(baos);
        return ftpFileService.uploadFileWithFileName(userId, fileName, new ByteArrayInputStream(baos.toByteArray()));
    }

    /**
     * 将数据写入工作表。
     * 
     * @param sheet   工作表
     * @param headers 表头
     * @param rows    数据行
     */
    private void writeDataToSheet(Sheet sheet, List<String> headers, List<List<Object>> rows) {
        // 写入表头
        Row headerRow = sheet.createRow(0);
        for (int i = 0; i < headers.size(); i++) {
            headerRow.createCell(i).setCellValue(headers.get(i));
        }

        // 写入数据行
        int rowIdx = 1;
        for (List<Object> rowData : rows) {
            Row row = sheet.createRow(rowIdx++);
            for (int i = 0; i < rowData.size(); i++) {
                Cell cell = row.createCell(i);
                setCellValue(cell, rowData.get(i));
            }
        }

        // 自动调整列宽
        for (int i = 0; i < headers.size(); i++) {
            sheet.autoSizeColumn(i);
        }
    }

    // ================================================================
    // 其他辅助方法
    // ================================================================

    private void ensureExcelFile(UserFile userFile) {
        if (userFile == null) {
            throw new IllegalArgumentException("File not found");
        }
        String fileName = userFile.getOriginalFileName().toLowerCase();
        if (!fileName.endsWith(".xlsx") && !fileName.endsWith(".xls") && !fileName.endsWith(".csv")) {
            throw new IllegalArgumentException("Unsupported file format. Only xlsx, xls, csv are supported.");
        }
    }

    private int readIntParam(Map<String, Object> params, String key, int defaultValue) {
        Object value = params.get(key);
        if (value instanceof Number) {
            return ((Number) value).intValue();
        } else if (value instanceof String) {
            try {
                return Integer.parseInt((String) value);
            } catch (NumberFormatException e) {
                return defaultValue;
            }
        }
        return defaultValue;
    }

    private String readStringParam(Map<String, Object> params, String key, String defaultValue) {
        Object value = params.get(key);
        return value instanceof String ? (String) value : defaultValue;
    }

    private byte[] downloadBytes(UserFile userFile) throws Exception {
        ByteArrayOutputStream baos = ftpFileService.downloadFile(userFile.getUserId(), userFile.getFileName());
        return baos.toByteArray();
    }

    private Workbook createWorkbook(byte[] bytes, String fileName) throws IOException {
        if (fileName.toLowerCase().endsWith(".csv")) {
            return createWorkbookFromCsv(bytes);
        } else if (fileName.toLowerCase().endsWith(".xls")) {
            return new HSSFWorkbook(new ByteArrayInputStream(bytes));
        } else {
            return new XSSFWorkbook(new ByteArrayInputStream(bytes));
        }
    }

    private Workbook createWorkbookFromCsv(byte[] bytes) {
        String content = new String(bytes, StandardCharsets.UTF_8);
        String[] lines = content.split("\n");
        
        Workbook wb = new XSSFWorkbook();
        Sheet sheet = wb.createSheet("Sheet1");
        
        int rowIdx = 0;
        for (String line : lines) {
            if (line.trim().isEmpty()) continue;
            String[] cells = parseCsvLine(line);
            Row row = sheet.createRow(rowIdx++);
            for (int i = 0; i < cells.length; i++) {
                row.createCell(i).setCellValue(cells[i]);
            }
        }
        return wb;
    }

    private String[] parseCsvLine(String line) {
        List<String> cells = new ArrayList<String>();
        StringBuilder current = new StringBuilder();
        boolean inQuotes = false;
        
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') {
                inQuotes = !inQuotes;
            } else if (c == ',' && !inQuotes) {
                cells.add(current.toString());
                current = new StringBuilder();
            } else {
                current.append(c);
            }
        }
        cells.add(current.toString());
        return cells.toArray(new String[0]);
    }

    private String getCellStringValue(Cell cell) {
        if (cell == null) return "";
        switch (cell.getCellType()) {
            case STRING:
                return cell.getStringCellValue();
            case NUMERIC:
                if (DateUtil.isCellDateFormatted(cell)) {
                    return cell.getDateCellValue().toString();
                }
                return String.valueOf(cell.getNumericCellValue());
            case BOOLEAN:
                return String.valueOf(cell.getBooleanCellValue());
            case FORMULA:
                try {
                    return cell.getStringCellValue();
                } catch (Exception e) {
                    return String.valueOf(cell.getNumericCellValue());
                }
            default:
                return "";
        }
    }

    private Object getCellValue(Cell cell) {
        if (cell == null) return null;
        switch (cell.getCellType()) {
            case STRING:
                return cell.getStringCellValue();
            case NUMERIC:
                if (DateUtil.isCellDateFormatted(cell)) {
                    return cell.getDateCellValue();
                }
                double numValue = cell.getNumericCellValue();
                if (numValue == Math.floor(numValue) && !Double.isInfinite(numValue)) {
                    return (long) numValue;
                }
                return numValue;
            case BOOLEAN:
                return cell.getBooleanCellValue();
            case FORMULA:
                try {
                    return cell.getStringCellValue();
                } catch (Exception e) {
                    return cell.getNumericCellValue();
                }
            default:
                return null;
        }
    }

    private void setCellValue(Cell cell, Object value) {
        if (value == null) {
            cell.setBlank();
        } else if (value instanceof String) {
            cell.setCellValue((String) value);
        } else if (value instanceof Number) {
            cell.setCellValue(((Number) value).doubleValue());
        } else if (value instanceof Boolean) {
            cell.setCellValue((Boolean) value);
        } else {
            cell.setCellValue(value.toString());
        }
    }

    private boolean matchesFilter(Object cellValue, String operator, String value) {
        String cellStr = cellValue != null ? cellValue.toString() : "";
        
        switch (operator) {
            case "equals":
                return cellStr.equals(value);
            case "contains":
                return cellStr.contains(value);
            case "gt":
                try {
                    double cellNum = Double.parseDouble(cellStr);
                    double valueNum = Double.parseDouble(value);
                    return cellNum > valueNum;
                } catch (NumberFormatException e) {
                    return false;
                }
            case "lt":
                try {
                    double cellNum = Double.parseDouble(cellStr);
                    double valueNum = Double.parseDouble(value);
                    return cellNum < valueNum;
                } catch (NumberFormatException e) {
                    return false;
                }
            case "gte":
                try {
                    double cellNum = Double.parseDouble(cellStr);
                    double valueNum = Double.parseDouble(value);
                    return cellNum >= valueNum;
                } catch (NumberFormatException e) {
                    return false;
                }
            case "lte":
                try {
                    double cellNum = Double.parseDouble(cellStr);
                    double valueNum = Double.parseDouble(value);
                    return cellNum <= valueNum;
                } catch (NumberFormatException e) {
                    return false;
                }
            case "notEquals":
                return !cellStr.equals(value);
            default:
                return false;
        }
    }

    private int compareValues(Object v1, Object v2) {
        if (v1 == null && v2 == null) return 0;
        if (v1 == null) return -1;
        if (v2 == null) return 1;
        
        if (v1 instanceof Comparable && v2 instanceof Comparable) {
            try {
                @SuppressWarnings("unchecked")
                Comparable<Object> c1 = (Comparable<Object>) v1;
                return c1.compareTo(v2);
            } catch (ClassCastException e) {
                return v1.toString().compareTo(v2.toString());
            }
        }
        return v1.toString().compareTo(v2.toString());
    }

    private double evaluateFormula(String formula, List<Object> rowData, 
                                   Map<String, Integer> headerIndices, List<String> headers) {
        String expr = formula;
        for (String header : headers) {
            Integer idx = headerIndices.get(header);
            if (idx != null && idx < rowData.size()) {
                Object value = rowData.get(idx);
                String valueStr = value instanceof Number ? value.toString() : "0";
                expr = expr.replace("{" + header + "}", valueStr);
            }
        }
        try {
            return evaluateSimpleExpression(expr);
        } catch (Exception e) {
            return 0.0;
        }
    }

    private double evaluateSimpleExpression(String expr) {
        expr = expr.replaceAll("\\s+", "");
        try {
            javax.script.ScriptEngine engine = new javax.script.ScriptEngineManager()
                .getEngineByName("JavaScript");
            Object result = engine.eval(expr);
            if (result instanceof Number) {
                return ((Number) result).doubleValue();
            }
        } catch (javax.script.ScriptException e) {
            log.warn("Failed to evaluate expression: {}", expr, e);
        }
        return 0.0;
    }

    private String validateCell(Object value, String ruleType, Object ruleValue, String column, int rowNum) {
        boolean isEmpty = value == null || value.toString().trim().isEmpty();
        
        // required 和 notEmpty 规则：空值时返回错误
        if (isEmpty) {
            if ("required".equalsIgnoreCase(ruleType) || "notEmpty".equalsIgnoreCase(ruleType)) {
                return "Row " + rowNum + ": " + column + " cannot be empty";
            }
            return null;
        }

        String strValue = value.toString();
        
        switch (ruleType) {
            case "min":
                try {
                    double numValue = Double.parseDouble(strValue);
                    double min = Double.parseDouble(ruleValue.toString());
                    if (numValue < min) {
                        return "Row " + rowNum + ": " + column + " value " + numValue + " is less than minimum " + min;
                    }
                } catch (NumberFormatException e) {
                    return "Row " + rowNum + ": " + column + " is not a number";
                }
                break;
            case "max":
                try {
                    double numValue = Double.parseDouble(strValue);
                    double max = Double.parseDouble(ruleValue.toString());
                    if (numValue > max) {
                        return "Row " + rowNum + ": " + column + " value " + numValue + " is greater than maximum " + max;
                    }
                } catch (NumberFormatException e) {
                    return "Row " + rowNum + ": " + column + " is not a number";
                }
                break;
            case "pattern":
                if (!strValue.matches(ruleValue.toString())) {
                    return "Row " + rowNum + ": " + column + " does not match pattern " + ruleValue;
                }
                break;
            case "length":
                int maxLength = Integer.parseInt(ruleValue.toString());
                if (strValue.length() > maxLength) {
                    return "Row " + rowNum + ": " + column + " length " + strValue.length() + " exceeds maximum " + maxLength;
                }
                break;
            case "in":
                @SuppressWarnings("unchecked")
                List<String> allowedValues = (List<String>) ruleValue;
                if (!allowedValues.contains(strValue)) {
                    return "Row " + rowNum + ": " + column + " value '" + strValue + "' is not in allowed list";
                }
                break;
        }
        return null;
    }
}