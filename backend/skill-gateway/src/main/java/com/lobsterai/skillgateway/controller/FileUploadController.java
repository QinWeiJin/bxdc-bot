package com.lobsterai.skillgateway.controller;

import com.lobsterai.skillgateway.dto.FileParseResult;
import com.lobsterai.skillgateway.entity.UserFile;
import com.lobsterai.skillgateway.mapper.UserFileMapper;
import com.lobsterai.skillgateway.service.FileParseService;
import com.lobsterai.skillgateway.service.FtpFileService;
import com.lobsterai.skillgateway.util.AamTokenUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import javax.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 文件上传 Controller。
 * <p>
 * 衔接模块 2 前端 + wgj 后端解析通道。前端通过本端点上传文件，gateway
 * 负责 FTP 存储 + 调 FileParseService 解析 + 写回 user_files.parsed_summary。
 * 解析逻辑全部走 wgj 已有服务（{@link FtpFileService} + {@link FileParseService}），
 * 本 Controller 仅做编排（约 40 行），不写业务逻辑。
 * </p>
 *
 * <h3>端点</h3>
 * <ul>
 *   <li>{@code POST /api/files/upload} — multipart 上传文件，自动解析 + 写 DB</li>
 * </ul>
 *
 * <h3>请求</h3>
 * <ul>
 *   <li>{@code file} — 原始文件二进制（form-data）</li>
 *   <li>{@code X-User-Id} header — 用户 AAM ID（由 {@code FileAccessInterceptor} 强制要求）</li>
 * </ul>
 *
 * <h3>响应</h3>
 * <pre>{@code
 * {
 *   "fileId": 123,
 *   "parsedSummary": "{...JSON...}"
 * }
 * }</pre>
 */
@RestController
@RequestMapping("/api/files")
public class FileUploadController {

    private static final Logger log = LoggerFactory.getLogger(FileUploadController.class);

    /** 单文件最大 10 MiB（与前端 10MB 校验对齐） */
    private static final long MAX_FILE_SIZE = 10L * 1024L * 1024L;

    private final FtpFileService ftpFileService;
    private final FileParseService fileParseService;
    private final UserFileMapper userFileMapper;

    public FileUploadController(FtpFileService ftpFileService,
                                FileParseService fileParseService,
                                UserFileMapper userFileMapper) {
        this.ftpFileService = ftpFileService;
        this.fileParseService = fileParseService;
        this.userFileMapper = userFileMapper;
    }

    /**
     * 上传文件 + 自动解析 + 写回 user_files.parsed_summary。
     * <p>
     * 流程：X-User-Id 校验 → FTP 存文件 → 建 UserFile 实体 → 写 DB →
     * 调 FileParseService.parseAndPersist()（解析 + 回写 parsed_summary）。
     * </p>
     *
     * @param file    上传的文件（multipart form-data "file" 字段）
     * @param request HTTP 请求（从 X-User-Id header 提取 userId）
     * @return 200 + { fileId, parsedSummary } / 4xx 错误响应
     */
    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Map<String, Object>> upload(
            @RequestParam("file") MultipartFile file,
            HttpServletRequest request
    ) {
        // 1. 文件基本校验
        if (file == null || file.isEmpty()) {
            return error(HttpStatus.BAD_REQUEST, "FILE_EMPTY", "请选择要上传的文件");
        }
        if (file.getSize() > MAX_FILE_SIZE) {
            return error(HttpStatus.PAYLOAD_TOO_LARGE, "FILE_TOO_LARGE",
                    "文件大小超过 10MB，请修改后重试");
        }
        String originalFileName = file.getOriginalFilename();
        if (originalFileName == null || originalFileName.trim().isEmpty()) {
            return error(HttpStatus.BAD_REQUEST, "FILE_NO_NAME", "文件名为空");
        }

        // 2. userId 校验（FileAccessInterceptor 已保证 X-User-Id 存在）
        String userId;
        try {
            userId = AamTokenUtil.requireUserId(request);
        } catch (IllegalArgumentException e) {
            return error(HttpStatus.UNAUTHORIZED, "MISSING_USER_ID", e.getMessage());
        }

        log.info("File upload start: user={}, originalName={}, size={}",
                userId, originalFileName, file.getSize());

        try {
            // 3. 调 wgj 已有 FtpFileService — 存 FTP，返回 fullPath (e.g. /files/uid/abc.docx)
            String ftpPath = ftpFileService.uploadFile(userId, originalFileName, file.getInputStream());

            // 4. 建 UserFile 实体
            UserFile userFile = new UserFile();
            userFile.setUserId(userId);
            userFile.setOriginalFileName(originalFileName);
            userFile.setFileName(extractStorageFileName(ftpPath));
            userFile.setFileSize(file.getSize());
            userFile.setFileType(extractExtension(originalFileName));
            userFile.setFtpPath(ftpPath);
            // uploadTime 由 MyBatis-Plus FieldFill.INSERT 自动填充

            // 5. 写 DB（MyBatis-Plus AUTO id 写入后回填 userFile.getId()）
            userFileMapper.insert(userFile);

            // 6. 调 wgj 已有 FileParseService — 解析 + 回写 parsed_summary
            // 内部会 FTP 下载 + parserRouter 解析 + 序列化 + userFileMapper.updateById()
            FileParseResult parseResult = fileParseService.parseAndPersist(userFile);

            log.info("File upload done: fileId={}, type={}, originalName={}",
                    userFile.getId(), parseResult.getFileType(), originalFileName);

            // 7. 返回 fileId + parsedSummary
            Map<String, Object> body = new LinkedHashMap<String, Object>();
            body.put("fileId", userFile.getId());
            body.put("parsedSummary", userFile.getParsedSummary());
            body.put("fileName", originalFileName);
            body.put("fileType", parseResult.getFileType());
            return ResponseEntity.ok(body);

        } catch (IllegalArgumentException e) {
            log.warn("File upload rejected: user={}, name={}, reason={}",
                    userId, originalFileName, e.getMessage());
            return error(HttpStatus.BAD_REQUEST, "UPLOAD_REJECTED", e.getMessage());
        } catch (IOException e) {
            log.error("File upload FTP error: user={}, name={}", userId, originalFileName, e);
            return error(HttpStatus.BAD_GATEWAY, "FTP_UNAVAILABLE",
                    "文件存储服务暂时不可用，请稍后重试");
        } catch (Exception e) {
            log.error("File upload unexpected error: user={}, name={}", userId, originalFileName, e);
            return error(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR",
                    "上传失败：" + e.getMessage());
        }
    }

    private ResponseEntity<Map<String, Object>> error(HttpStatus status, String code, String message) {
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("error", code);
        body.put("message", message);
        return ResponseEntity.status(status).body(body);
    }

    private String extractStorageFileName(String ftpPath) {
        if (ftpPath == null) return "";
        int slash = ftpPath.lastIndexOf('/');
        return slash >= 0 ? ftpPath.substring(slash + 1) : ftpPath;
    }

    private String extractExtension(String fileName) {
        if (fileName == null) return "";
        int dot = fileName.lastIndexOf('.');
        return dot < 0 ? "" : fileName.substring(dot + 1).toLowerCase();
    }
}
