package com.lobsterai.skillgateway.controller;

import com.lobsterai.skillgateway.dto.ExcelParseErrorResponse;
import com.lobsterai.skillgateway.dto.ExcelParseResponse;
import com.lobsterai.skillgateway.exception.ExcelParseException;
import com.lobsterai.skillgateway.service.ExcelParserService;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

/**
 * Excel 表格解析控制器。
 *
 * 提供 POST /api/features/file/parse-excel 端点。
 * 支持 .xlsx 和 .xls 格式，单文件 ≤ 1 MiB。
 */
@RestController
@RequestMapping("/api/features/file")
public class ExcelParserController {

    private static final long MAX_FILE_SIZE = 1 * 1024 * 1024;

    private final ExcelParserService excelParserService;

    public ExcelParserController(ExcelParserService excelParserService) {
        this.excelParserService = excelParserService;
    }

    @PostMapping(value = "/parse-excel", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> parseExcel(@RequestParam("file") MultipartFile file) {
        if (file == null || file.isEmpty()) {
            return ResponseEntity.badRequest()
                    .body(new ExcelParseErrorResponse("EXCEL_EMPTY", "请选择要解析的 Excel 文件"));
        }

        if (file.getSize() > MAX_FILE_SIZE) {
            return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
                    .body(new ExcelParseErrorResponse("EXCEL_TOO_LARGE",
                            "文件大小超过限制（最大 1 MiB）", MAX_FILE_SIZE));
        }

        String fileName = file.getOriginalFilename();
        if (fileName == null || (!fileName.toLowerCase().endsWith(".xls") && !fileName.toLowerCase().endsWith(".xlsx"))) {
            return ResponseEntity.badRequest()
                    .body(new ExcelParseErrorResponse("EXCEL_UNSUPPORTED_TYPE", "仅支持 .xls 和 .xlsx 格式"));
        }

        try {
            byte[] fileBytes = file.getBytes();
            ExcelParserService.ExcelParseResult result = excelParserService.parse(fileBytes, fileName);
            return ResponseEntity.ok(new ExcelParseResponse(result.text, result.sheetCount));
        } catch (ExcelParseException e) {
            return ResponseEntity.badRequest()
                    .body(new ExcelParseErrorResponse(e.getCode(), e.getMessage()));
        } catch (IOException e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(new ExcelParseErrorResponse("EXCEL_READ_ERROR", "文件读取失败：" + e.getMessage()));
        }
    }
}
