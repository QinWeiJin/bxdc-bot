package com.lobsterai.skillgateway.controller;

import com.lobsterai.skillgateway.dto.WordParseErrorResponse;
import com.lobsterai.skillgateway.dto.WordParseResponse;
import com.lobsterai.skillgateway.exception.WordParseException;
import com.lobsterai.skillgateway.service.WordParserService;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

/**
 * Word 文档解析控制器。
 *
 * 提供 POST /api/features/file/parse-word 端点。
 * 支持 .docx 和 .doc 格式，单文件 ≤ 5 MiB。
 */
@RestController
@RequestMapping("/api/features/file")
public class WordParserController {

    private static final long MAX_FILE_SIZE = 5 * 1024 * 1024;

    private final WordParserService wordParserService;

    public WordParserController(WordParserService wordParserService) {
        this.wordParserService = wordParserService;
    }

    @PostMapping(value = "/parse-word", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> parseWord(@RequestParam("file") MultipartFile file) {
        if (file == null || file.isEmpty()) {
            return ResponseEntity.badRequest()
                    .body(new WordParseErrorResponse("WORD_EMPTY", "请选择要解析的 Word 文件"));
        }

        if (file.getSize() > MAX_FILE_SIZE) {
            return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
                    .body(new WordParseErrorResponse("WORD_TOO_LARGE",
                            "文件大小超过限制（最大 5 MiB）", MAX_FILE_SIZE));
        }

        String fileName = file.getOriginalFilename();
        if (fileName == null || (!fileName.toLowerCase().endsWith(".doc") && !fileName.toLowerCase().endsWith(".docx"))) {
            return ResponseEntity.badRequest()
                    .body(new WordParseErrorResponse("WORD_UNSUPPORTED_TYPE", "仅支持 .doc 和 .docx 格式"));
        }

        try {
            byte[] fileBytes = file.getBytes();
            WordParserService.WordParseResult result = wordParserService.parse(fileBytes, fileName);
            return ResponseEntity.ok(new WordParseResponse(result.text, result.pageCount));
        } catch (WordParseException e) {
            return ResponseEntity.badRequest()
                    .body(new WordParseErrorResponse(e.getCode(), e.getMessage()));
        } catch (IOException e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(new WordParseErrorResponse("WORD_READ_ERROR", "文件读取失败：" + e.getMessage()));
        }
    }
}
