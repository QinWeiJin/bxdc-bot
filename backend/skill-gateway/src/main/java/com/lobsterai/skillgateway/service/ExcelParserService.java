package com.lobsterai.skillgateway.service;

import com.lobsterai.skillgateway.exception.ExcelParseException;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Excel 表格纯文本提取服务。
 *
 * 支持 .xlsx（OOXML）和 .xls（旧二进制格式）。
 * 遍历所有 sheet，输出 tab 分隔的行文本，sheet 间空行分隔。
 */
@Service
public class ExcelParserService {

    private static final int TEXT_MAX_BYTES = 80 * 1024;

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
