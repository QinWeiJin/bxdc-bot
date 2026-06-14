package com.lobsterai.skillgateway.controller;

import com.lobsterai.skillgateway.dto.FileParseResult;
import com.lobsterai.skillgateway.entity.UserFile;
import com.lobsterai.skillgateway.mapper.UserFileMapper;
import com.lobsterai.skillgateway.service.FileParseService;
import com.lobsterai.skillgateway.service.FtpFileService;
import com.lobsterai.skillgateway.util.AamTokenUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartFile;

import javax.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.time.LocalDateTime;
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
            // 3. 调 wgj 已有 FtpFileService — 存本地磁盘，返回 fullPath (e.g. /files/uid/abc.docx)
            //    内部用 Files.copy + 64KB 缓冲，零拷贝，大文件落盘约 80ms
            String ftpPath = ftpFileService.uploadFile(userId, originalFileName, file.getInputStream());

            // 4. 建 UserFile 实体
            UserFile userFile = new UserFile();
            userFile.setUserId(userId);
            userFile.setOriginalFileName(originalFileName);
            userFile.setFileName(extractStorageFileName(ftpPath));
            userFile.setFileSize(file.getSize());
            userFile.setFileType(extractExtension(originalFileName));
            userFile.setFtpPath(ftpPath);
            // uploadTime 手动填充：wgj 的 MybatisPlusConfig MetaObjectHandler
            // 只处理 createdAt/updatedAt，未注册 uploadTime 的 fill handler，
            // 故此处显式 set 兜底（避免 SQLIntegrityConstraintViolationException）
            userFile.setUploadTime(LocalDateTime.now());

            // 5. 写 DB（MyBatis-Plus AUTO id 写入后回填 userFile.getId()）
            userFileMapper.insert(userFile);

            // 6. 解析改为异步（关键优化点）：
            //    旧逻辑：同步等 parseAndPersist 完成（PDF/Word 大文件可能 1-3 秒），
            //           HTTP 连接挂死，前端转圈圈。
            //    新逻辑：立刻返回 fileId + status=PARSING，前端可调 GET /api/files/{id} 轮询。
            //           解析失败/成功都直接 updateById 回写 parsedSummary。
            fileParseService.parseAndPersistAsync(userFile);

            log.info("File upload queued: fileId={}, originalName={}, size={}",
                    userFile.getId(), originalFileName, file.getSize());

            // 7. 立即返回：fileId + 解析状态。parsedSummary 在异步完成后填充，
            //    前端通过 GET /api/files/{id} 轮询或等通知中心推送
            Map<String, Object> body = new LinkedHashMap<String, Object>();
            body.put("fileId", userFile.getId());
            body.put("fileName", originalFileName);
            body.put("fileType", userFile.getFileType());
            body.put("size", file.getSize());
            body.put("status", "PARSING");
            body.put("message", "File uploaded. Parse running in background; poll /api/files/" + userFile.getId());
            return ResponseEntity.accepted().body(body);

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

    /**
     * 轮询文件上传/解析状态（异步解析后给前端用）。
     * <p>
     * 配合 {@link #upload} 的 202 Accepted 响应：前端拿到 fileId 后
     * 间隔轮询本端点，直到 {@code status} 变为 {@code READY} 或 {@code FAILED}。
     * </p>
     * <p>
     * 状态机：
     * <ul>
     *   <li>{@code PARSING} — 文件落盘 + 入库成功，异步解析进行中</li>
     *   <li>{@code READY} — 解析完成，{@code parsedSummary} 已填充</li>
     *   <li>{@code FAILED} — 解析异常（当前实现走 log error + 不写 parsedSummary，
     *       实际通过 {@code parsedSummary} 是否为空判定）</li>
     * </ul>
     * </p>
     *
     * @param id      文件主键（{@code user_files.id}）
     * @param request HTTP 请求（{@code X-User-Id} 头）
     * @return 200 + 文件元数据 + 解析状态 / 404 文件不存在
     */
    @GetMapping("/{id}")
    public ResponseEntity<Map<String, Object>> getFile(
            @PathVariable("id") Long id,
            HttpServletRequest request
    ) {
        String userId;
        try {
            userId = AamTokenUtil.requireUserId(request);
        } catch (IllegalArgumentException e) {
            return error(HttpStatus.UNAUTHORIZED, "MISSING_USER_ID", e.getMessage());
        }

        UserFile uf = userFileMapper.selectById(id);
        if (uf == null) {
            return error(HttpStatus.NOT_FOUND, "FILE_NOT_FOUND", "File not found: " + id);
        }
        if (!userId.equals(uf.getUserId())) {
            return error(HttpStatus.NOT_FOUND, "FILE_NOT_FOUND", "File not found: " + id);
        }

        boolean parsed = uf.getParsedSummary() != null && !uf.getParsedSummary().isEmpty();
        String status = parsed ? "READY" : "PARSING";

        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("fileId", uf.getId());
        body.put("fileName", uf.getOriginalFileName());
        body.put("fileType", uf.getFileType());
        body.put("size", uf.getFileSize());
        body.put("uploadTime", uf.getUploadTime() != null ? uf.getUploadTime().toString() : null);
        body.put("downloadUrl", uf.getDownloadUrl());
        body.put("status", status);
        if (parsed) {
            body.put("parsedSummary", uf.getParsedSummary());
        }
        return ResponseEntity.ok(body);
    }

    /**
     * 下载用户文件（流式返回，避免大文件全量加载到堆内存）。
     * <p>
     * 端点：{@code GET /api/files/download/{id}}
     * </p>
     * <p>
     * 鉴权：要求 {@code X-User-Id} header，userFile 行的 userId 必须与 header 一致。
     * 鉴权失败/文件不存在一律返回 404（不暴露存在性）。
     * </p>
     * <p>
     * 文件实际内容走 {@link FtpFileService#openForDownload} 的 InputStream，
     * 配 {@link InputStreamResource} 让 Spring 写入时不会先把整个文件读入堆。
     * </p>
     *
     * @param id      文件主键（{@code user_files.id}）
     * @param request HTTP 请求（{@code X-User-Id} 头）
     * @return 200 + 文件流 / 404
     */
    @GetMapping("/download/{id}")
    public ResponseEntity<?> downloadFile(
            @PathVariable("id") Long id,
            HttpServletRequest request
    ) {
        String userId;
        try {
            userId = AamTokenUtil.requireUserId(request);
        } catch (IllegalArgumentException e) {
            return error(HttpStatus.UNAUTHORIZED, "MISSING_USER_ID", e.getMessage());
        }

        UserFile uf = userFileMapper.selectById(id);
        if (uf == null || !userId.equals(uf.getUserId())) {
            return error(HttpStatus.NOT_FOUND, "FILE_NOT_FOUND", "File not found: " + id);
        }
        if (uf.getFileName() == null) {
            return error(HttpStatus.NOT_FOUND, "FILE_NOT_FOUND", "File not found: " + id);
        }

        try {
            java.io.InputStream in = ftpFileService.openForDownload(userId, uf.getFileName());
            long size = uf.getFileSize() == null ? -1L : uf.getFileSize();

            HttpHeaders headers = new HttpHeaders();
            headers.setContentDisposition(
                    org.springframework.http.ContentDisposition.attachment()
                            .filename(uf.getOriginalFileName() == null ? uf.getFileName() : uf.getOriginalFileName())
                            .build());
            MediaType ct = resolveContentType(uf);
            headers.setContentType(ct);
            if (size > 0) {
                headers.setContentLength(size);
            }

            return ResponseEntity.ok()
                    .headers(headers)
                    .body(new InputStreamResource(in));
        } catch (java.io.IOException e) {
            log.error("File download failed: user={}, fileId={}, name={}", userId, id, uf.getFileName(), e);
            return error(HttpStatus.INTERNAL_SERVER_ERROR, "DOWNLOAD_FAILED", "Download failed: " + e.getMessage());
        }
    }

    /**
     * 根据扩展名推断下载的 Content-Type。
     * <p>
     * JDK 1.8 兼容：使用 {@code URLConnection.guessContentTypeFromName}，
     * 走 jdk 内置 mime table，无三方包依赖（AGENTS.md 5.1）。
     * </p>
     */
    private static MediaType resolveContentType(UserFile uf) {
        String name = uf.getOriginalFileName() != null ? uf.getOriginalFileName() : uf.getFileName();
        if (name == null) return MediaType.APPLICATION_OCTET_STREAM;
        try {
            String mime = java.net.URLConnection.guessContentTypeFromName(name);
            if (mime != null) {
                return MediaType.parseMediaType(mime);
            }
        } catch (Exception ignored) {
            // 推断失败时落到 OCTET_STREAM
        }
        // 常见类型兜底
        String lower = name.toLowerCase();
        if (lower.endsWith(".docx")) return MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.wordprocessingml.document");
        if (lower.endsWith(".doc"))  return MediaType.parseMediaType("application/msword");
        if (lower.endsWith(".xlsx")) return MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        if (lower.endsWith(".xls"))  return MediaType.parseMediaType("application/vnd.ms-excel");
        if (lower.endsWith(".pptx")) return MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.presentationml.presentation");
        if (lower.endsWith(".ppt"))  return MediaType.parseMediaType("application/vnd.ms-powerpoint");
        if (lower.endsWith(".pdf"))  return MediaType.parseMediaType("application/pdf");
        if (lower.endsWith(".csv")) return MediaType.parseMediaType("text/csv");
        if (lower.endsWith(".md") || lower.endsWith(".txt")) return MediaType.TEXT_PLAIN;
        if (lower.endsWith(".json")) return MediaType.APPLICATION_JSON;
        if (lower.endsWith(".png"))  return MediaType.IMAGE_PNG;
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return MediaType.IMAGE_JPEG;
        if (lower.endsWith(".gif"))  return MediaType.IMAGE_GIF;
        return MediaType.APPLICATION_OCTET_STREAM;
    }

    /**
     * 处理 Tomcat multipart 阶段抛出的超大文件异常。
     * <p>
     * Spring 在请求体解析阶段就拒绝超过 {@code spring.servlet.multipart.max-file-size}
     * 的文件，本 Controller 的 try-catch 抓不到，必须用 {@link ExceptionHandler} 兜底。
     * 注：Spring 5.3 的 {@code @ExceptionHandler} 对 dispatcher 解析阶段抛的异常不生效
     * （已知问题，Spring 6 已修），实际靠 Spring Boot 默认 error handler 转 500。
     * 此处 handler 仅作文档化意图，不期望真正触发。
     * </p>
     */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<Map<String, Object>> handleMaxUploadSize(MaxUploadSizeExceededException e) {
        log.warn("File upload rejected (multipart size limit): max={}", e.getMaxUploadSize());
        return error(HttpStatus.PAYLOAD_TOO_LARGE, "FILE_TOO_LARGE",
                "文件大小超过 10MB，请修改后重试");
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
