package com.lobsterai.skillgateway.service.tools;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lobsterai.skillgateway.dto.FileParseResult;
import com.lobsterai.skillgateway.dto.FileToolResponse;
import com.lobsterai.skillgateway.entity.UserFile;
import com.lobsterai.skillgateway.mapper.UserFileMapper;
import com.lobsterai.skillgateway.service.FileParseService;
import com.lobsterai.skillgateway.service.FileToolService;
import com.lobsterai.skillgateway.service.FtpFileService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 文件管理工具（模块五 / 6.1-6.4）。
 * <p>
 * 提供 4 个 API：file_list、file_delete、file_clear_all、file_detail。
 * 完整实现替换了 FileToolService 中的占位版本，
 * 支持：完整字段列表、二次确认流程、真实删除/清空操作、解析摘要反序列化。
 * </p>
 *
 * <h3>二次确认机制</h3>
 * <p>
 * 删除类 API（file_delete、file_clear_all）支持二次确认：
 * <ul>
 *   <li>首次调用：返回 requiresConfirmation=true + 目标清单，<b>不</b>执行删除</li>
 *   <li>LLM 在对话中引导用户确认文件名（"确认删除 example.docx 吗？"）</li>
 *   <li>用户确认后再次调用，传入 params.confirmed=true 才真正执行</li>
 * </ul>
 * </p>
 *
 * <h3>JDK 1.8 兼容约束</h3>
 * <ul>
 *   <li>不用 {@code var}、{@code List.of()}、{@code String.formatted()}</li>
 *   <li>不用 {@code switch} 表达式、{@code instanceof} 模式匹配</li>
 *   <li>Map 显式声明泛型 {@code Map<String, Object>}</li>
 * </ul>
 */
@Service
public class FileManageService {

    private static final Logger log = LoggerFactory.getLogger(FileManageService.class);

    private final FileToolService fileToolService;
    private final FtpFileService ftpFileService;
    private final UserFileMapper userFileMapper;
    private final FileParseService fileParseService;
    private final ObjectMapper objectMapper;

    @Autowired
    public FileManageService(FileToolService fileToolService,
                             FtpFileService ftpFileService,
                             UserFileMapper userFileMapper,
                             FileParseService fileParseService,
                             ObjectMapper objectMapper) {
        this.fileToolService = fileToolService;
        this.ftpFileService = ftpFileService;
        this.userFileMapper = userFileMapper;
        this.fileParseService = fileParseService;
        this.objectMapper = objectMapper;
    }

    /** Spring 启动后覆盖 FileToolService 中的占位实现为完整版。 */
    @PostConstruct
    public void registerHandlers() {
        fileToolService.registerHandler("file_list", new FileToolService.ToolHandler() {
            @Override
            public FileToolResponse handle(UserFile userFile, Map<String, Object> params, String userId) throws Exception {
                return fileList(params, userId);
            }
        });
        fileToolService.registerHandler("file_delete", new FileToolService.ToolHandler() {
            @Override
            public FileToolResponse handle(UserFile userFile, Map<String, Object> params, String userId) throws Exception {
                return fileDelete(userFile, params, userId);
            }
        });
        fileToolService.registerHandler("file_clear_all", new FileToolService.ToolHandler() {
            @Override
            public FileToolResponse handle(UserFile userFile, Map<String, Object> params, String userId) throws Exception {
                return fileClearAll(params, userId);
            }
        });
        fileToolService.registerHandler("file_detail", new FileToolService.ToolHandler() {
            @Override
            public FileToolResponse handle(UserFile userFile, Map<String, Object> params, String userId) throws Exception {
                return fileDetail(userFile, params, userId);
            }
        });
        log.info("FileManageService registered 4 handlers (full implementation, replaces placeholders): file_list/delete/clear_all/detail");
    }

    // ================================================================
    // 6.1 file_list — 列出用户所有文件
    // ================================================================

    /**
     * 列出当前用户已上传的所有文件。
     * <p>
     * params.fileType — 可选，按文件类型过滤（如 "docx"/"xlsx"/"md"，不区分大小写）
     * params.keyword — 可选，按文件名模糊匹配（不区分大小写）
     * params.sortBy — 可选，排序字段：uploadTime/size/name（默认 uploadTime）
     * params.order — 可选，asc/desc（默认 desc）
     * params.page — 可选，页码（1-based，默认 1）
     * params.pageSize — 可选，每页数量（默认 50，max 200）
     * </p>
     */
    public FileToolResponse fileList(Map<String, Object> params, String userId) {
        try {
            List<UserFile> allFiles = userFileMapper.findByUserId(userId);
            // 过滤
            String fileTypeFilter = readStringParam(params, "fileType", "").toLowerCase();
            String keyword = readStringParam(params, "keyword", "").toLowerCase();
            List<UserFile> filtered = new ArrayList<UserFile>();
            for (UserFile f : allFiles) {
                if (!fileTypeFilter.isEmpty()) {
                    String ft = f.getFileType() == null ? "" : f.getFileType().toLowerCase();
                    if (!ft.equals(fileTypeFilter)) continue;
                }
                if (!keyword.isEmpty()) {
                    String name = f.getOriginalFileName() == null ? "" : f.getOriginalFileName().toLowerCase();
                    if (!name.contains(keyword)) continue;
                }
                filtered.add(f);
            }
            // 排序
            String sortBy = readStringParam(params, "sortBy", "uploadTime");
            boolean asc = !"desc".equalsIgnoreCase(readStringParam(params, "order", "desc"));
            final String sortField = sortBy;
            final boolean ascending = asc;
            Collections.sort(filtered, new java.util.Comparator<UserFile>() {
                @Override
                public int compare(UserFile a, UserFile b) {
                    int cmp;
                    if ("size".equalsIgnoreCase(sortField)) {
                        long la = a.getFileSize() == null ? 0L : a.getFileSize();
                        long lb = b.getFileSize() == null ? 0L : b.getFileSize();
                        cmp = Long.compare(la, lb);
                    } else if ("name".equalsIgnoreCase(sortField)) {
                        String na = a.getOriginalFileName() == null ? "" : a.getOriginalFileName();
                        String nb = b.getOriginalFileName() == null ? "" : b.getOriginalFileName();
                        cmp = na.compareTo(nb);
                    } else {
                        // 默认 uploadTime
                        long ta = a.getUploadTime() == null ? 0L : localDateTimeToEpoch(a.getUploadTime());
                        long tb = b.getUploadTime() == null ? 0L : localDateTimeToEpoch(b.getUploadTime());
                        cmp = Long.compare(ta, tb);
                    }
                    return ascending ? cmp : -cmp;
                }
            });
            // 分页
            int page = Math.max(1, readIntParam(params, "page", 1));
            int pageSize = readIntParam(params, "pageSize", 50);
            if (pageSize < 1) pageSize = 50;
            if (pageSize > 200) pageSize = 200;
            int total = filtered.size();
            int fromIdx = Math.min((page - 1) * pageSize, total);
            int toIdx = Math.min(fromIdx + pageSize, total);
            List<UserFile> pageList = new ArrayList<UserFile>();
            for (int i = fromIdx; i < toIdx; i++) {
                pageList.add(filtered.get(i));
            }
            // 序列化
            List<Map<String, Object>> fileList = new ArrayList<Map<String, Object>>();
            for (UserFile uf : pageList) {
                fileList.add(toFileInfo(uf));
            }
            Map<String, Object> result = new LinkedHashMap<String, Object>();
            result.put("count", fileList.size());
            result.put("totalCount", total);
            result.put("page", page);
            result.put("pageSize", pageSize);
            result.put("totalPages", (total + pageSize - 1) / pageSize);
            result.put("files", fileList);
            if (fileList.isEmpty()) {
                result.put("message", "No files found.");
            }
            return FileToolResponse.ok(result, "user:" + userId);
        } catch (Exception e) {
            log.error("file_list failed for user {}", userId, e);
            return FileToolResponse.error("file_list failed: " + e.getMessage(), "user:" + userId);
        }
    }

    // ================================================================
    // 6.2 file_delete — 删除指定文件（二次确认）
    // ================================================================

    /**
     * 删除指定文件，二次确认流程：
     * <ol>
     *   <li>首次调用（无 confirmed）→ 返回 requiresConfirmation + 目标文件名/ID</li>
     *   <li>LLM 在对话中询问用户："确认删除 example.docx 吗？"</li>
     *   <li>用户确认后再次调用，传入 params.confirmed=true → 真正删除（FTP + DB）</li>
     * </ol>
     * <p>
     * params.confirmed — true 表示用户已确认
     * </p>
     */
    public FileToolResponse fileDelete(UserFile userFile, Map<String, Object> params, String userId) {
        try {
            boolean confirmed = readBoolParam(params, "confirmed", false);

            if (!confirmed) {
                // 第一次调用：返回二次确认请求
                Map<String, Object> result = new LinkedHashMap<String, Object>();
                result.put("requiresConfirmation", true);
                result.put("message", "请向用户确认是否删除文件 \"" + userFile.getOriginalFileName() + "\"（ID=" + userFile.getId() + "）。"
                        + "用户确认后，请用相同的 fileRef 再次调用 file_delete，并设置 params.confirmed=true。");
                result.put("targetFile", toFileInfo(userFile));
                result.put("confirmationPrompt", "确认删除 " + userFile.getOriginalFileName() + " 吗？");
                return FileToolResponse.ok(result, userFile.getOriginalFileName());
            }

            // 用户已确认 → 真正删除
            String storageName = userFile.getFileName();
            String originalName = userFile.getOriginalFileName();
            // 1. 删 FTP 文件
            boolean ftpDeleted = false;
            try {
                ftpDeleted = ftpFileService.deleteFile(userId, storageName);
            } catch (Exception ftpEx) {
                log.warn("FTP delete failed for {}/{}: {}", userId, storageName, ftpEx.getMessage());
            }
            // 2. 删 DB 记录（无论 FTP 是否成功，DB 记录都要删）
            int dbDeleted = userFileMapper.deleteById(userFile.getId());

            Map<String, Object> result = new LinkedHashMap<String, Object>();
            result.put("message", "File deleted: " + originalName);
            result.put("fileName", originalName);
            result.put("fileId", userFile.getId());
            result.put("storageName", storageName);
            result.put("ftpDeleted", ftpDeleted);
            result.put("dbDeleted", dbDeleted);
            return FileToolResponse.ok(result, originalName);
        } catch (Exception e) {
            log.error("file_delete failed for fileId={}", userFile.getId(), e);
            return FileToolResponse.error("file_delete failed: " + e.getMessage(), userFile.getOriginalFileName());
        }
    }

    // ================================================================
    // 6.3 file_clear_all — 清空所有文件（二次确认）
    // ================================================================

    /**
     * 清空当前用户的所有文件，二次确认流程同 file_delete。
     * <p>
     * params.confirmed — true 表示用户已确认
     * </p>
     */
    public FileToolResponse fileClearAll(Map<String, Object> params, String userId) {
        try {
            List<UserFile> files = userFileMapper.findByUserId(userId);
            boolean confirmed = readBoolParam(params, "confirmed", false);

            if (!confirmed) {
                // 第一次调用：返回二次确认请求
                List<Map<String, Object>> fileList = new ArrayList<Map<String, Object>>();
                long totalSize = 0L;
                for (UserFile uf : files) {
                    fileList.add(toFileInfo(uf));
                    if (uf.getFileSize() != null) totalSize += uf.getFileSize();
                }
                Map<String, Object> result = new LinkedHashMap<String, Object>();
                result.put("requiresConfirmation", true);
                result.put("message", "请向用户确认是否清空全部 " + files.size() + " 个文件（共 " + formatSize(totalSize) + "）。"
                        + "用户确认后，请再次调用 file_clear_all，并设置 params.confirmed=true。");
                result.put("fileCount", files.size());
                result.put("totalSize", totalSize);
                result.put("files", fileList);
                result.put("confirmationPrompt", "确认清空全部 " + files.size() + " 个文件吗？此操作不可恢复。");
                return FileToolResponse.ok(result, "user:" + userId);
            }

            // 用户已确认 → 真正清空
            int ftpDeleted = 0;
            int dbDeleted = 0;
            List<String> deletedNames = new ArrayList<String>();
            try {
                ftpDeleted = ftpFileService.deleteAllFiles(userId);
            } catch (Exception ftpEx) {
                log.warn("FTP deleteAll failed for {}: {}", userId, ftpEx.getMessage());
            }
            for (UserFile uf : files) {
                int r = userFileMapper.deleteById(uf.getId());
                if (r > 0) {
                    dbDeleted++;
                    deletedNames.add(uf.getOriginalFileName());
                }
            }

            Map<String, Object> result = new LinkedHashMap<String, Object>();
            result.put("message", "All files cleared");
            result.put("fileCount", files.size());
            result.put("ftpDeleted", ftpDeleted);
            result.put("dbDeleted", dbDeleted);
            result.put("deletedNames", deletedNames);
            return FileToolResponse.ok(result, "user:" + userId);
        } catch (Exception e) {
            log.error("file_clear_all failed for user {}", userId, e);
            return FileToolResponse.error("file_clear_all failed: " + e.getMessage(), "user:" + userId);
        }
    }

    // ================================================================
    // 6.4 file_detail — 查看文件详情
    // ================================================================

    /**
     * 查看文件详情（名称、大小、类型、上传时间、解析摘要）。
     * <p>
     * params.includeParseResult — 是否反序列化 parsedSummary 返回完整解析结果（默认 true）
     * params.previewChars — 返回内容预览的字符数（默认 500）
     * </p>
     */
    public FileToolResponse fileDetail(UserFile userFile, Map<String, Object> params, String userId) {
        try {
            // 当 userFile 为 null 时（通过 skill 入口直接调用，未经过 fileId/fileRef 解析），
            // 尝试从 params 中的 fileName 或 fileRef 查找文件
            if (userFile == null) {
                String fileName = readStringParam(params, "fileName", "");
                if (fileName.isEmpty()) {
                    fileName = readStringParam(params, "fileRef", "");
                }
                if (!fileName.isEmpty()) {
                    // 多文件同名时取最新上传的一个
                    List<UserFile> candidates = userFileMapper.findByUserIdAndFileNameLimit(userId, fileName, 1);
                    if (candidates != null && !candidates.isEmpty()) {
                        userFile = candidates.get(0);
                    } else {
                        return FileToolResponse.error("File not found by name: '" + fileName + "'. Use 'file_list' to see available files.", fileName);
                    }
                } else {
                    return FileToolResponse.error("fileId, fileName, or fileRef is required for file_detail");
                }
            }
            boolean includeParse = readBoolParam(params, "includeParseResult", true);
            int previewChars = readIntParam(params, "previewChars", 500);

            Map<String, Object> detail = new LinkedHashMap<String, Object>();
            detail.put("id", userFile.getId());
            detail.put("fileName", userFile.getOriginalFileName());
            detail.put("storageName", userFile.getFileName());
            detail.put("fileSize", userFile.getFileSize());
            detail.put("fileSizeReadable", formatSize(userFile.getFileSize()));
            detail.put("fileType", userFile.getFileType());
            detail.put("uploadTime", userFile.getUploadTime() != null ? userFile.getUploadTime().toString() : null);
            detail.put("downloadUrl", userFile.getDownloadUrl());
            detail.put("parsed", fileParseService.isParseComplete(userFile));

            // 解析摘要
            if (includeParse && fileParseService.isParseComplete(userFile)) {
                String summary = userFile.getParsedSummary();
                if (summary != null && !summary.isEmpty()) {
                    try {
                        // 反序列化为 Map（避免直接依赖 FileParseResult 完整类）
                        Map<String, Object> parsed = objectMapper.readValue(summary, new TypeReference<Map<String, Object>>() {});
                        // 截断 contentPreview
                        Object preview = parsed.get("contentPreview");
                        if (preview instanceof String) {
                            String ps = (String) preview;
                            if (ps.length() > previewChars) {
                                parsed.put("contentPreview", ps.substring(0, previewChars) + "...");
                                parsed.put("contentPreviewTruncated", true);
                            }
                        }
                        detail.put("parsedSummary", parsed);
                    } catch (Exception jsonEx) {
                        log.warn("Failed to deserialize parsedSummary for fileId={}: {}", userFile.getId(), jsonEx.getMessage());
                        // 降级返回原始 JSON 字符串（截断）
                        String s = summary.length() > previewChars ? summary.substring(0, previewChars) + "..." : summary;
                        detail.put("parsedSummary", s);
                        detail.put("parsedSummaryTruncated", true);
                    }
                }
            } else {
                detail.put("parsed", false);
                detail.put("parsedMessage", "File not parsed yet. The summary will be generated on first use.");
            }
            return FileToolResponse.ok(detail, userFile.getOriginalFileName());
        } catch (Exception e) {
            log.error("file_detail failed for fileId={}", userFile.getId(), e);
            return FileToolResponse.error("file_detail failed: " + e.getMessage(), userFile.getOriginalFileName());
        }
    }

    // ================================================================
    // 内部辅助
    // ================================================================

    /** 统一文件信息序列化（用于 file_list、file_delete、file_clear_all） */
    private Map<String, Object> toFileInfo(UserFile uf) {
        Map<String, Object> item = new LinkedHashMap<String, Object>();
        item.put("id", uf.getId());
        item.put("fileName", uf.getOriginalFileName());
        item.put("storageName", uf.getFileName());
        item.put("fileSize", uf.getFileSize());
        item.put("fileSizeReadable", formatSize(uf.getFileSize()));
        item.put("fileType", uf.getFileType());
        item.put("uploadTime", uf.getUploadTime() != null ? uf.getUploadTime().toString() : null);
        item.put("downloadUrl", uf.getDownloadUrl());
        item.put("parsed", fileParseService.isParseComplete(uf));
        return item;
    }

    /** 人类可读文件大小 */
    private String formatSize(Long size) {
        if (size == null || size <= 0) return "0 B";
        long s = size;
        if (s < 1024) return s + " B";
        if (s < 1024L * 1024) return String.format("%.1f KB", s / 1024.0);
        if (s < 1024L * 1024 * 1024) return String.format("%.1f MB", s / (1024.0 * 1024));
        return String.format("%.2f GB", s / (1024.0 * 1024 * 1024));
    }

    /** LocalDateTime 转 epoch 毫秒（用于排序） */
    private long localDateTimeToEpoch(LocalDateTime ldt) {
        if (ldt == null) return 0L;
        return ldt.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
    }

    // ========== 参数读取 ==========

    private String readStringParam(Map<String, Object> params, String key, String def) {
        if (params == null) return def;
        Object v = params.get(key);
        return v == null ? def : String.valueOf(v);
    }

    private int readIntParam(Map<String, Object> params, String key, int def) {
        if (params == null) return def;
        Object v = params.get(key);
        if (v instanceof Number) {
            return ((Number) v).intValue();
        }
        if (v instanceof String) {
            try {
                return Integer.parseInt((String) v);
            } catch (NumberFormatException ignored) {
            }
        }
        return def;
    }

    private boolean readBoolParam(Map<String, Object> params, String key, boolean def) {
        if (params == null) return def;
        Object v = params.get(key);
        if (v instanceof Boolean) {
            return (Boolean) v;
        }
        if (v instanceof String) {
            return Boolean.parseBoolean((String) v);
        }
        return def;
    }
}
