package com.lobsterai.skillgateway.controller;

import com.lobsterai.skillgateway.dto.PptParseErrorResponse;
import com.lobsterai.skillgateway.dto.PptParseResponse;
import com.lobsterai.skillgateway.exception.PptParseException;
import com.lobsterai.skillgateway.service.PptParserService;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

/**
 * PPT 解析控制器。
 *
 * 提供 POST /api/features/file/parse-ppt 端点，用于解析 PPT 文件提取纯文本。
 *
 * 支持格式：
 * - .pptx（Office Open XML 格式）
 * - .ppt （旧二进制格式）
 *
 * 限额：
 * - 单文件 ≤ 10 MiB（HTTP 层面 + 应用层双重校验）
 */
@RestController
@RequestMapping("/api/features/file")
public class PptParserController {

    /** 单文件最大 10 MiB */
    private static final long MAX_FILE_SIZE = 10 * 1024 * 1024;

    private final PptParserService pptParserService;

    public PptParserController(PptParserService pptParserService) {
        this.pptParserService = pptParserService;
    }

    /**
     * 解析 PPT 文件，提取纯文本内容。
     *
     * @param file 上传的 PPT 文件（.ppt 或 .pptx）
     * @return 解析结果（text + slideCount）
     */
    @PostMapping(value = "/parse-ppt", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> parsePpt(@RequestParam("file") MultipartFile file) {
        // 文件校验
        if (file == null || file.isEmpty()) {
            return ResponseEntity.badRequest()
                    .body(new PptParseErrorResponse("PPT_EMPTY", "请选择要解析的 PPT 文件"));
        }

        // 大小校验
        if (file.getSize() > MAX_FILE_SIZE) {
            return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
                    .body(new PptParseErrorResponse("PPT_TOO_LARGE",
                            "文件大小超过限制（最大 10 MiB）", MAX_FILE_SIZE));
        }

        // 扩展名校验
        String fileName = file.getOriginalFilename();
        if (fileName == null || (!fileName.toLowerCase().endsWith(".ppt") && !fileName.toLowerCase().endsWith(".pptx"))) {
            return ResponseEntity.badRequest()
                    .body(new PptParseErrorResponse("PPT_UNSUPPORTED_TYPE", "仅支持 .ppt 和 .pptx 格式"));
        }

        try {
            byte[] fileBytes = file.getBytes();
            PptParserService.PptParseResult result = pptParserService.parse(fileBytes, fileName);
            return ResponseEntity.ok(new PptParseResponse(result.text, result.slideCount));
        } catch (PptParseException e) {
            return ResponseEntity.badRequest()
                    .body(new PptParseErrorResponse(e.getCode(), e.getMessage()));
        } catch (IOException e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(new PptParseErrorResponse("PPT_READ_ERROR", "文件读取失败：" + e.getMessage()));
        }
    }
}