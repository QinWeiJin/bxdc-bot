package com.lobsterai.skillgateway.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lobsterai.skillgateway.config.FtpConfig;
import com.lobsterai.skillgateway.dto.FileParseResult;
import com.lobsterai.skillgateway.entity.UserFile;
import com.lobsterai.skillgateway.mapper.UserFileMapper;
import com.lobsterai.skillgateway.service.parser.FileParserRouter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.util.List;

/**
 * 文件解析编排服务。
 * <p>
 * 负责文件解析的完整流程编排：
 * <ol>
 *   <li>从 FTP 下载文件内容</li>
 *   <li>通过 FileParserRouter 路由到对应解析器</li>
 *   <li>填充 downloadUrl 到解析结果</li>
 *   <li>将解析结果 JSON 存回 UserFile.parsedSummary</li>
 *   <li>组装系统提示词上下文，供 LLM 注入</li>
 * </ol>
 * </p>
 */
@Service
public class FileParseService {

    private static final Logger log = LoggerFactory.getLogger(FileParseService.class);

    private final FtpFileService ftpFileService;
    private final FileParserRouter parserRouter;
    private final UserFileMapper userFileMapper;
    private final FtpConfig ftpConfig;
    private final ObjectMapper objectMapper;

    public FileParseService(FtpFileService ftpFileService,
                            FileParserRouter parserRouter,
                            UserFileMapper userFileMapper,
                            FtpConfig ftpConfig,
                            ObjectMapper objectMapper) {
        this.ftpFileService = ftpFileService;
        this.parserRouter = parserRouter;
        this.userFileMapper = userFileMapper;
        this.ftpConfig = ftpConfig;
        this.objectMapper = objectMapper;
    }

    /**
     * 解析单个文件并更新数据库。
     * <p>
     * 完整流程：FTP 下载 → 路由解析 → 填充 downloadUrl → 更新 DB → 返回结果。
     * </p>
     *
     * @param userFile 文件实体（需已有 id/fileName/userId）
     * @return 解析结果（含 downloadUrl）
     * @throws Exception 下载或解析失败
     */
    public FileParseResult parseAndPersist(UserFile userFile) throws Exception {
        return parseAndPersistInternal(userFile);
    }

    /**
     * 异步执行 {@link #parseAndPersistInternal}。
     * <p>
     * 优化点：上传接口在文件落盘 + DB 写入后立即返回，不等解析。
     * 解析在独立线程池（默认 {@code SimpleAsyncTaskExecutor}）执行，
     * 完成后通过 DB 回写 + 通知中心（如有）告知前端。
     * </p>
     * <p>
     * 注意：{@code UserFile} 实体是 Spring 注入的 mapper 创建的同实例，
     * 异步线程读它的 {@code id/fileName/userId} 字段是安全的。
     * </p>
     */
    @Async
    public void parseAndPersistAsync(UserFile userFile) {
        try {
            parseAndPersistInternal(userFile);
        } catch (Exception e) {
            log.error("Async parse failed for file id={}: {}", userFile.getId(), e.getMessage(), e);
        }
    }

    private FileParseResult parseAndPersistInternal(UserFile userFile) throws Exception {
        // 1. 从 FTP 下载文件
        ByteArrayOutputStream baos = ftpFileService.downloadFile(userFile.getUserId(), userFile.getFileName());
        byte[] fileBytes = baos.toByteArray();

        // 2. 路由到解析器
        FileParseResult result;
        try {
            result = parserRouter.parse(fileBytes, userFile.getOriginalFileName());
        } catch (IllegalArgumentException e) {
            // 不支持的文件类型：创建一个最小结果
            log.warn("Unsupported file type for '{}': {}", userFile.getOriginalFileName(), e.getMessage());
            result = new FileParseResult();
            result.setFileType(userFile.getFileType());
            result.setContentPreview("Unsupported file type. File stored for download.");
        }

        // 3. 填充基础字段
        result.setOriginalFileName(userFile.getOriginalFileName());
        result.setFileSize(userFile.getFileSize());

        // 4. 填充 downloadUrl（4.1.2）
        if (userFile.getDownloadUrl() != null) {
            result.setDownloadUrl(userFile.getDownloadUrl());
        } else {
            String downloadUrl = ftpConfig.buildDownloadUrl(userFile.getId());
            result.setDownloadUrl(downloadUrl);
            userFile.setDownloadUrl(downloadUrl);
        }

        // 5. 序列化并存储 parsedSummary
        String json = objectMapper.writeValueAsString(result);
        userFile.setParsedSummary(json);
        userFileMapper.updateById(userFile);

        log.info("File parsed and persisted: id={}, type={}, originalName={}",
                userFile.getId(), result.getFileType(), userFile.getOriginalFileName());
        return result;
    }

    /**
     * 解析单个文件（不更新数据库），用于后续 Tool 调用时重新解析。
     */
    public FileParseResult parseOnly(UserFile userFile) throws Exception {
        ByteArrayOutputStream baos = ftpFileService.downloadFile(userFile.getUserId(), userFile.getFileName());
        byte[] fileBytes = baos.toByteArray();

        FileParseResult result = parserRouter.parse(fileBytes, userFile.getOriginalFileName());
        result.setOriginalFileName(userFile.getOriginalFileName());
        result.setFileSize(userFile.getFileSize());
        if (userFile.getDownloadUrl() != null) {
            result.setDownloadUrl(userFile.getDownloadUrl());
        }
        return result;
    }

    /**
     * 组装系统提示词上下文（4.8.1）。
     * <p>
     * 将文件解析结果转化为可供 LLM 理解的系统提示词片段。
     * 包含文件元数据和解析摘要。
     * </p>
     *
     * @param userFile 文件实体（parsedSummary 需已填充）
     * @return 系统提示词片段字符串
     */
    public String buildSystemPromptContext(UserFile userFile) {
        if (userFile.getParsedSummary() == null || userFile.getParsedSummary().isEmpty()) {
            return "[文件] " + userFile.getOriginalFileName()
                    + " (" + formatFileSize(userFile.getFileSize()) + ")";
        }

        try {
            FileParseResult result = objectMapper.readValue(userFile.getParsedSummary(), FileParseResult.class);
            return buildPromptFromResult(result);
        } catch (Exception e) {
            log.warn("Failed to parse FileParseResult JSON, using fallback: {}", e.getMessage());
            return "[文件] " + userFile.getOriginalFileName()
                    + " (" + formatFileSize(userFile.getFileSize()) + ")"
                    + "\n[摘要] " + truncatePreview(userFile.getParsedSummary(), 300);
        }
    }

    /**
     * 组装已上传文件列表的系统提示词（4.8.1 批量版本）。
     * <p>
     * 供 upload 完成后、LLM 首轮对话前调用。
     * </p>
     *
     * @param userFiles 当前 session 的上传文件列表
     * @return 系统提示词片段
     */
    public String buildSystemPromptForFiles(List<UserFile> userFiles) {
        if (userFiles == null || userFiles.isEmpty()) {
            return "";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("### 用户已上传的文件\n");
        sb.append("以下是用户在当前会话中上传的文件及其内容摘要，你需要根据这些信息回答用户的问题。\n\n");

        int index = 1;
        for (UserFile uf : userFiles) {
            sb.append("**文件 ").append(index).append(":** ").append(uf.getOriginalFileName());
            sb.append(" (").append(formatFileSize(uf.getFileSize())).append(")");
            if (uf.getFileType() != null) {
                sb.append(" 类型: ").append(uf.getFileType());
            }
            sb.append("\n");

            if (uf.getParsedSummary() != null && !uf.getParsedSummary().isEmpty()) {
                try {
                    FileParseResult result = objectMapper.readValue(uf.getParsedSummary(), FileParseResult.class);
                    sb.append("- 类型: ").append(result.getFileType() != null ? result.getFileType() : "unknown").append("\n");
                    if (result.getContentPreview() != null) {
                        sb.append("- 内容预览: ").append(truncatePreview(result.getContentPreview(), 200)).append("\n");
                    }
                    if (result.getOutline() != null && !result.getOutline().isEmpty()) {
                        sb.append("- 章节标题: ");
                        for (FileParseResult.OutlineItem item : result.getOutline()) {
                            sb.append(item.getText()).append("; ");
                        }
                        sb.append("\n");
                    }
                    if (result.getSheetCount() != null) {
                        sb.append("- Sheet 数量: ").append(result.getSheetCount()).append("\n");
                    }
                    if (result.getDownloadUrl() != null) {
                        sb.append("- 下载链接: ").append(result.getDownloadUrl()).append("\n");
                    }
                } catch (Exception e) {
                    sb.append("- 摘要: ").append(truncatePreview(uf.getParsedSummary(), 200)).append("\n");
                }
            }
            sb.append("\n");
            index++;
        }
        return sb.toString();
    }

    /**
     * 标记上传任务完成（4.8.2）。
     * <p>
     * 文件解析完成 → parsedSummary 已填充即表示上传任务完成。
     * 如果需要额外状态标记可在调用方处理。
     * </p>
     *
     * @param userFile 文件实体
     * @return true 如果解析已完成
     */
    public boolean isParseComplete(UserFile userFile) {
        return userFile != null
                && userFile.getParsedSummary() != null
                && !userFile.getParsedSummary().isEmpty();
    }

    // ========== 内部方法 ==========

    private String buildPromptFromResult(FileParseResult result) {
        StringBuilder sb = new StringBuilder();
        sb.append("[文件] ").append(result.getOriginalFileName());

        if (result.getFileSize() != null) {
            sb.append(" (").append(formatFileSize(result.getFileSize())).append(")");
        }
        if (result.getFileType() != null) {
            sb.append(" 类型: ").append(result.getFileType());
        }
        sb.append("\n");

        // Word 文件
        if (result.getOutline() != null && !result.getOutline().isEmpty()) {
            sb.append("[章节结构]\n");
            appendOutline(sb, result.getOutline(), 0);
        }

        if (result.getParagraphCount() != null) {
            sb.append("[段落数] ").append(result.getParagraphCount()).append("\n");
        }
        if (result.getTableCount() != null && result.getTableCount() > 0) {
            sb.append("[表格数] ").append(result.getTableCount()).append("\n");
        }

        // Excel 文件
        if (result.getSheetCount() != null) {
            sb.append("[Sheet数量] ").append(result.getSheetCount()).append("\n");
        }

        if (result.getContentPreview() != null && !result.getContentPreview().isEmpty()) {
            sb.append("[内容预览]\n").append(truncatePreview(result.getContentPreview(), 300)).append("\n");
        }

        if (result.getDownloadUrl() != null) {
            sb.append("[下载链接] ").append(result.getDownloadUrl()).append("\n");
        }

        return sb.toString();
    }

    private void appendOutline(StringBuilder sb, List<FileParseResult.OutlineItem> items, int depth) {
        for (FileParseResult.OutlineItem item : items) {
            for (int i = 0; i < depth; i++) sb.append("  ");
            sb.append("- ").append(item.getText());
            if (item.getCharCount() != null) sb.append(" (").append(item.getCharCount()).append("字)");
            sb.append("\n");
            if (item.getChildren() != null && !item.getChildren().isEmpty()) {
                appendOutline(sb, item.getChildren(), depth + 1);
            }
        }
    }

    private String formatFileSize(Long bytes) {
        if (bytes == null || bytes == 0) return "0 B";
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        return String.format("%.2f MB", bytes / (1024.0 * 1024.0));
    }

    private String truncatePreview(String text, int maxLen) {
        if (text == null) return "";
        String trimmed = text.trim();
        return trimmed.length() <= maxLen ? trimmed : trimmed.substring(0, maxLen) + "...";
    }
}
