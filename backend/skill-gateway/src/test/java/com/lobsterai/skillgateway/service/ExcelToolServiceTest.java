package com.lobsterai.skillgateway.service;

import com.lobsterai.skillgateway.config.FtpConfig;
import com.lobsterai.skillgateway.dto.ExcelOperationResult;
import com.lobsterai.skillgateway.dto.ExcelOperationResult.*;
import com.lobsterai.skillgateway.entity.UserFile;
import com.lobsterai.skillgateway.mapper.UserFileMapper;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.LocalDateTime;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

public class ExcelToolServiceTest {

    private FtpFileService ftpFileService;
    private FtpConfig ftpConfig;
    private UserFileMapper userFileMapper;

    private ExcelToolService excelToolService;

    @BeforeEach
    public void setUp() {
        ftpFileService = org.mockito.Mockito.mock(FtpFileService.class);
        ftpConfig = org.mockito.Mockito.mock(FtpConfig.class);
        userFileMapper = org.mockito.Mockito.mock(UserFileMapper.class);
        
        excelToolService = new ExcelToolService(ftpFileService, ftpConfig, userFileMapper);
    }

    @Test
    public void testRead_Success() throws Exception {
        Long fileId = 1L;
        String userId = "testUser";
        UserFile userFile = createTestUserFile(fileId, userId, "test.xlsx");

        when(userFileMapper.selectById(fileId)).thenReturn(userFile);
        when(ftpFileService.downloadFile(userId, userFile.getFileName()))
                .thenReturn(createTestExcelBytes());

        ExcelOperationResult result = excelToolService.read(fileId, userId);

        assertTrue(result.isSuccess());
        assertEquals("read", result.getOperation());
        assertNotNull(result.getResult());
        assertEquals(3, result.getResult().getColumns().size());
        assertEquals(2, result.getRowCount());
    }

    @Test
    public void testRead_FileNotFound() {
        Long fileId = 1L;
        when(userFileMapper.selectById(fileId)).thenReturn(null);

        ExcelOperationResult result = excelToolService.read(fileId, "testUser");

        assertFalse(result.isSuccess());
        assertEquals("文件不存在", result.getMessage());
    }

    @Test
    public void testFilter_Success() throws Exception {
        Long fileId = 1L;
        String userId = "testUser";
        UserFile userFile = createTestUserFile(fileId, userId, "test.xlsx");

        when(userFileMapper.selectById(fileId)).thenReturn(userFile);
        when(ftpFileService.downloadFile(userId, userFile.getFileName()))
                .thenReturn(createTestExcelBytes());

        List<FilterCriteria> criteria = new ArrayList<FilterCriteria>();
        criteria.add(new FilterCriteria("Name", "equals", "Alice"));

        ExcelOperationResult result = excelToolService.filter(fileId, userId, criteria);

        assertTrue(result.isSuccess());
        assertEquals("filter", result.getOperation());
        assertEquals(1, result.getRowCount());
    }

    @Test
    public void testSort_Success() throws Exception {
        Long fileId = 1L;
        String userId = "testUser";
        UserFile userFile = createTestUserFile(fileId, userId, "test.xlsx");

        when(userFileMapper.selectById(fileId)).thenReturn(userFile);
        when(ftpFileService.downloadFile(userId, userFile.getFileName()))
                .thenReturn(createTestExcelBytes());

        List<SortSpec> sortSpecs = new ArrayList<SortSpec>();
        sortSpecs.add(new SortSpec("Age", "desc"));

        ExcelOperationResult result = excelToolService.sort(fileId, userId, sortSpecs);

        assertTrue(result.isSuccess());
        assertEquals("sort", result.getOperation());
        assertNotNull(result.getResult());
    }

    @Test
    public void testSelectColumns_Success() throws Exception {
        Long fileId = 1L;
        String userId = "testUser";
        UserFile userFile = createTestUserFile(fileId, userId, "test.xlsx");

        when(userFileMapper.selectById(fileId)).thenReturn(userFile);
        when(ftpFileService.downloadFile(userId, userFile.getFileName()))
                .thenReturn(createTestExcelBytes());

        List<String> columns = Arrays.asList("Name", "Age");

        ExcelOperationResult result = excelToolService.selectColumns(fileId, userId, columns);

        assertTrue(result.isSuccess());
        assertEquals("select_columns", result.getOperation());
        assertEquals(2, result.getColCount());
    }

    @Test
    public void testAggregate_Success() throws Exception {
        Long fileId = 1L;
        String userId = "testUser";
        UserFile userFile = createTestExcelWithNumbers(fileId, userId);

        when(userFileMapper.selectById(fileId)).thenReturn(userFile);
        when(ftpFileService.downloadFile(userId, userFile.getFileName()))
                .thenReturn(createTestExcelWithNumbersBytes());

        List<AggregationSpec> aggregations = new ArrayList<AggregationSpec>();
        aggregations.add(new AggregationSpec("Score", "sum"));

        ExcelOperationResult result = excelToolService.aggregate(fileId, userId, "Class", aggregations);

        assertTrue(result.isSuccess());
        assertEquals("aggregate", result.getOperation());
    }

    @Test
    public void testCalculate_Success() throws Exception {
        Long fileId = 1L;
        String userId = "testUser";
        UserFile userFile = createTestExcelWithNumbers(fileId, userId);

        when(userFileMapper.selectById(fileId)).thenReturn(userFile);
        when(ftpFileService.downloadFile(userId, userFile.getFileName()))
                .thenReturn(createTestExcelWithNumbersBytes());

        List<CalculateExpression> expressions = new ArrayList<CalculateExpression>();
        expressions.add(new CalculateExpression("ScoreDouble", "{Score} * 2"));

        ExcelOperationResult result = excelToolService.calculate(fileId, userId, expressions);

        assertTrue(result.isSuccess());
        assertEquals("calculate", result.getOperation());
        assertEquals(4, result.getColCount());
    }

    @Test
    public void testClean_Success() throws Exception {
        Long fileId = 1L;
        String userId = "testUser";
        UserFile userFile = createTestUserFile(fileId, userId, "test.xlsx");

        when(userFileMapper.selectById(fileId)).thenReturn(userFile);
        when(ftpFileService.downloadFile(userId, userFile.getFileName()))
                .thenReturn(createTestExcelBytes());

        ExcelOperationResult result = excelToolService.clean(fileId, userId, "trim");

        assertTrue(result.isSuccess());
        assertEquals("clean", result.getOperation());
    }

    @Test
    public void testPivot_Success() throws Exception {
        Long fileId = 1L;
        String userId = "testUser";
        UserFile userFile = createTestExcelWithNumbers(fileId, userId);

        when(userFileMapper.selectById(fileId)).thenReturn(userFile);
        when(ftpFileService.downloadFile(userId, userFile.getFileName()))
                .thenReturn(createTestExcelWithNumbersBytes());

        ExcelOperationResult result = excelToolService.pivot(fileId, userId, "Class", "Subject", "Score");

        assertTrue(result.isSuccess());
        assertEquals("pivot", result.getOperation());
    }

    @Test
    public void testValidate_Success() throws Exception {
        Long fileId = 1L;
        String userId = "testUser";
        UserFile userFile = createTestUserFile(fileId, userId, "test.xlsx");

        when(userFileMapper.selectById(fileId)).thenReturn(userFile);
        when(ftpFileService.downloadFile(userId, userFile.getFileName()))
                .thenReturn(createTestExcelBytes());

        List<Map<String, Object>> rules = new ArrayList<Map<String, Object>>();
        Map<String, Object> rule = new LinkedHashMap<String, Object>();
        rule.put("column", "Age");
        rule.put("type", "number");
        rules.add(rule);

        ExcelOperationResult result = excelToolService.validate(fileId, userId, rules);

        assertTrue(result.isSuccess());
        assertEquals("validate", result.getOperation());
    }

    private UserFile createTestUserFile(Long id, String userId, String originalFileName) {
        UserFile userFile = new UserFile();
        userFile.setId(id);
        userFile.setUserId(userId);
        userFile.setOriginalFileName(originalFileName);
        userFile.setFileName("test-storage.xlsx");
        userFile.setFileSize(1024L);
        userFile.setFileType("xlsx");
        userFile.setFtpPath("/files/" + userId + "/test-storage.xlsx");
        userFile.setUploadTime(LocalDateTime.now());
        return userFile;
    }

    private UserFile createTestExcelWithNumbers(Long id, String userId) {
        UserFile userFile = new UserFile();
        userFile.setId(id);
        userFile.setUserId(userId);
        userFile.setOriginalFileName("scores.xlsx");
        userFile.setFileName("scores-storage.xlsx");
        userFile.setFileSize(1024L);
        userFile.setFileType("xlsx");
        userFile.setFtpPath("/files/" + userId + "/scores-storage.xlsx");
        userFile.setUploadTime(LocalDateTime.now());
        return userFile;
    }

    private ByteArrayOutputStream createTestExcelBytes() throws IOException {
        Workbook wb = new XSSFWorkbook();
        Sheet sheet = wb.createSheet("Sheet1");

        Row header = sheet.createRow(0);
        header.createCell(0).setCellValue("Name");
        header.createCell(1).setCellValue("Age");
        header.createCell(2).setCellValue("City");

        Row row1 = sheet.createRow(1);
        row1.createCell(0).setCellValue("Alice");
        row1.createCell(1).setCellValue(25);
        row1.createCell(2).setCellValue("Beijing");

        Row row2 = sheet.createRow(2);
        row2.createCell(0).setCellValue("Bob");
        row2.createCell(1).setCellValue(30);
        row2.createCell(2).setCellValue("Shanghai");

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        wb.write(baos);
        wb.close();
        return baos;
    }

    private ByteArrayOutputStream createTestExcelWithNumbersBytes() throws IOException {
        Workbook wb = new XSSFWorkbook();
        Sheet sheet = wb.createSheet("Sheet1");

        Row header = sheet.createRow(0);
        header.createCell(0).setCellValue("Class");
        header.createCell(1).setCellValue("Subject");
        header.createCell(2).setCellValue("Score");

        Row row1 = sheet.createRow(1);
        row1.createCell(0).setCellValue("Class A");
        row1.createCell(1).setCellValue("Math");
        row1.createCell(2).setCellValue(85);

        Row row2 = sheet.createRow(2);
        row2.createCell(0).setCellValue("Class A");
        row2.createCell(1).setCellValue("English");
        row2.createCell(2).setCellValue(90);

        Row row3 = sheet.createRow(3);
        row3.createCell(0).setCellValue("Class B");
        row3.createCell(1).setCellValue("Math");
        row3.createCell(2).setCellValue(80);

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        wb.write(baos);
        wb.close();
        return baos;
    }
}