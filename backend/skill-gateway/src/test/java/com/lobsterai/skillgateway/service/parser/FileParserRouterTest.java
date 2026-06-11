package com.lobsterai.skillgateway.service.parser;

import com.lobsterai.skillgateway.dto.FileParseResult;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;

/**
 * FileParserRouter 单元测试 (Java 8 兼容)
 */
public class FileParserRouterTest {

    private FileParserRouter router;
    private WordParser wordParser;
    private TxtMdParser txtMdParser;
    private PyParser pyParser;
    private ExcelParser excelParser;

    @BeforeEach
    public void setUp() {
        wordParser = new WordParser();
        txtMdParser = new TxtMdParser();
        pyParser = new PyParser();
        excelParser = new ExcelParser();
        
        router = new FileParserRouter(wordParser, txtMdParser, pyParser, excelParser);
    }

    @Test
    public void testRouteExcelXlsx() throws Exception {
        byte[] excelBytes = createTestExcel();
        FileParseResult result = router.parse(excelBytes, "test.xlsx");
        
        assertNotNull(result);
        assertEquals("excel", result.getFileType());
        assertEquals(1, result.getSheetCount());
        assertNotNull(result.getSheets());
        assertFalse(result.getSheets().isEmpty());
    }

    @Test
    public void testRouteExcelXls() throws Exception {
        byte[] excelBytes = createTestExcelXls();
        FileParseResult result = router.parse(excelBytes, "test.xls");
        
        assertNotNull(result);
        assertEquals("excel", result.getFileType());
    }

    @Test
    public void testRouteCsv() throws Exception {
        String csvContent = "姓名,年龄,城市\n张三,25,北京\n李四,30,上海";
        byte[] csvBytes = csvContent.getBytes("UTF-8");
        
        FileParseResult result = router.parse(csvBytes, "test.csv");
        
        assertNotNull(result);
        assertEquals("excel", result.getFileType());
    }

    @Test
    public void testRouteWordDocx() throws Exception {
        byte[] wordBytes = createTestWord();
        FileParseResult result = router.parse(wordBytes, "test.docx");
        
        assertNotNull(result);
        assertEquals("word", result.getFileType());
    }

    @Test
    public void testRouteTxt() throws Exception {
        String txtContent = "这是一个测试文件\n包含多行文本\n用于测试解析功能";
        byte[] txtBytes = txtContent.getBytes();
        
        FileParseResult result = router.parse(txtBytes, "test.txt");
        
        assertNotNull(result);
        assertEquals("txt", result.getFileType());
    }

    @Test
    public void testRouteMarkdown() throws Exception {
        String mdContent = "# 标题\n\n这是一段**粗体**文本。\n\n- 列表项1\n- 列表项2";
        byte[] mdBytes = mdContent.getBytes();
        
        FileParseResult result = router.parse(mdBytes, "test.md");
        
        assertNotNull(result);
        assertEquals("md", result.getFileType());
    }

    @Test
    public void testIsSupported() {
        assertTrue(router.isSupported("test.xlsx"));
        assertTrue(router.isSupported("test.xls"));
        assertTrue(router.isSupported("test.csv"));
        assertTrue(router.isSupported("test.docx"));
        assertTrue(router.isSupported("test.doc"));
        assertTrue(router.isSupported("test.txt"));
        assertTrue(router.isSupported("test.md"));
        assertTrue(router.isSupported("test.py"));
        
        assertFalse(router.isSupported("test.pdf"));
        assertFalse(router.isSupported("test.zip"));
        assertFalse(router.isSupported("test.exe"));
    }

    @Test
    public void testUnsupportedFileType() {
        byte[] dummyBytes = new byte[]{1, 2, 3};
        
        IllegalArgumentException exception = assertThrows(
            IllegalArgumentException.class, 
            new org.junit.jupiter.api.function.Executable() {
                @Override
                public void execute() throws Throwable {
                    router.parse(dummyBytes, "test.pdf");
                }
            }
        );
        
        assertNotNull(exception);
    }

    @Test
    public void testRegisterParser() throws Exception {
        FileParser mockParser = new FileParser() {
            @Override
            public FileParseResult parse(byte[] fileBytes, String fileName) throws Exception {
                FileParseResult result = new FileParseResult();
                result.setFileType("custom");
                return result;
            }

            @Override
            public String supportedType() {
                return "custom";
            }
        };

        router.registerParser("custom", mockParser);
        
        assertTrue(router.isSupported("test.custom"));
        
        byte[] dummyBytes = new byte[]{1, 2, 3};
        FileParseResult result = router.parse(dummyBytes, "test.custom");
        assertEquals("custom", result.getFileType());
    }

    private byte[] createTestExcel() throws IOException {
        Workbook workbook = new XSSFWorkbook();
        Sheet sheet = workbook.createSheet("Test Sheet");
        Row headerRow = sheet.createRow(0);
        Cell nameCell = headerRow.createCell(0);
        nameCell.setCellValue("Name");
        Cell ageCell = headerRow.createCell(1);
        ageCell.setCellValue("Age");
        
        Row dataRow = sheet.createRow(1);
        dataRow.createCell(0).setCellValue("Test");
        dataRow.createCell(1).setCellValue(25);
        
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        workbook.write(outputStream);
        workbook.close();
        return outputStream.toByteArray();
    }

    private byte[] createTestExcelXls() throws IOException {
        Workbook workbook = new org.apache.poi.hssf.usermodel.HSSFWorkbook();
        Sheet sheet = workbook.createSheet("Test Sheet");
        Row headerRow = sheet.createRow(0);
        headerRow.createCell(0).setCellValue("Name");
        Row dataRow = sheet.createRow(1);
        dataRow.createCell(0).setCellValue("Test");
        
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        workbook.write(outputStream);
        workbook.close();
        return outputStream.toByteArray();
    }

    private byte[] createTestWord() throws IOException {
        org.apache.poi.xwpf.usermodel.XWPFDocument doc = new org.apache.poi.xwpf.usermodel.XWPFDocument();
        doc.createParagraph().createRun().setText("Test content");
        
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        doc.write(outputStream);
        doc.close();
        return outputStream.toByteArray();
    }
}