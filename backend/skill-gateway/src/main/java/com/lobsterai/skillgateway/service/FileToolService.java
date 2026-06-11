package com.lobsterai.skillgateway.service;

import com.lobsterai.skillgateway.dto.FileToolRequest;
import com.lobsterai.skillgateway.dto.FileToolResponse;
import com.lobsterai.skillgateway.entity.UserFile;
import com.lobsterai.skillgateway.mapper.UserFileMapper;
import com.lobsterai.skillgateway.util.AamTokenUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import javax.servlet.http.HttpServletRequest;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 文件工具服务（5.1.1 调度核心）。
 * <p>
 * 负责根据 toolName 将请求分派到对应的处理器。
 * 文件工具按类别分组：file_*（文件管理）、word_*（Word 操作）、
 * txt_*（TXT 操作）、excel_*（Excel 操作）、md_*（MD 操作）。
 * </p>
 *
 * <h3>扩展方式</h3>
 * <p>
 * 后续任务（5.3 Word、5.4 TXT、5.5 MD、6 文件管理）通过
 * {@link #registerHandler(String, ToolHandler)} 注册具体的处理器实现。
 * 也可以让处理器 Bean 通过 {@code @PostConstruct} 自动注册。
 * </p>
 */
@Service
public class FileToolService {

    private static final Logger log = LoggerFactory.getLogger(FileToolService.class);

    private final FileRefResolver fileRefResolver;
    private final UserFileMapper userFileMapper;
    private final FileParseService fileParseService;
    private final Map<String, ToolHandler> handlers = new ConcurrentHashMap<String, ToolHandler>();

    public FileToolService(FileRefResolver fileRefResolver,
                           UserFileMapper userFileMapper,
                           FileParseService fileParseService) {
        this.fileRefResolver = fileRefResolver;
        this.userFileMapper = userFileMapper;
        this.fileParseService = fileParseService;
        initHandlers();
    }

    private void initHandlers() {
        // ===== 文件管理（task 6.x，基础版在此实现，完整版在后续任务扩展）=====
        handlers.put("file_list", new ToolHandler() {
            @Override
            public FileToolResponse handle(UserFile userFile, Map<String, Object> params, String userId) {
                return listFiles(userId);
            }
        });
        handlers.put("file_delete", new ToolHandler() {
            @Override
            public FileToolResponse handle(UserFile userFile, Map<String, Object> params, String userId) {
                return deleteFile(userFile, userId);
            }
        });
        handlers.put("file_clear_all", new ToolHandler() {
            @Override
            public FileToolResponse handle(UserFile userFile, Map<String, Object> params, String userId) {
                return clearAllFiles(userId);
            }
        });
        handlers.put("file_detail", new ToolHandler() {
            @Override
            public FileToolResponse handle(UserFile userFile, Map<String, Object> params, String userId) {
                return fileDetail(userFile);
            }
        });

        // ===== 占位：后续任务实现 =====
        // Word 操作（task 5.3）— 后续注册
        // TXT 操作（task 5.4）— 后续注册
        // Excel 操作（task 5.2）— 启雷注册
        // MD 操作（task 5.5）— 壮注册
    }

    /**
     * 注册一个工具处理器（供后续任务扩展）。
     *
     * @param toolName 工具名，如 "word_read"
     * @param handler  处理器
     */
    public void registerHandler(String toolName, ToolHandler handler) {
        handlers.put(toolName, handler);
        log.info("Registered file tool: {}", toolName);
    }

    /**
     * 调度文件工具请求（通过 SystemSkillService 统一入口）。
     * <p>
     * agent-core 通过 POST /api/system-skills/execute 调用，
     * arguments 中携带 fileRef 和 params。
     * </p>
     *
     * @param userId    用户 ID（从 X-User-Id 头获取）
     * @param toolName  工具名
     * @param arguments 请求参数（含 fileRef 和工具特定 params）
     * @return 统一响应
     */
    public FileToolResponse execute(String userId, String toolName, Map<String, Object> arguments) {
        if (toolName == null || toolName.trim().isEmpty()) {
            return FileToolResponse.error("toolName is required");
        }

        ToolHandler handler = handlers.get(toolName);
        if (handler == null) {
            return FileToolResponse.error("Unknown tool: " + toolName
                    + ". Available tools: " + String.join(", ", getAvailableTools()));
        }

        Map<String, Object> args = arguments != null ? arguments : Collections.<String, Object>emptyMap();
        String fileRef = args.get("fileRef") instanceof String ? (String) args.get("fileRef") : null;

        try {
            UserFile userFile = null;
            if (!isManagementTool(toolName)) {
                if (fileRef == null || fileRef.trim().isEmpty()) {
                    return FileToolResponse.error("fileRef is required for tool: " + toolName);
                }
                userFile = fileRefResolver.resolve(userId, fileRef);
            }
            // 从 arguments 提取工具特定 params（排除 fileRef）
            Map<String, Object> toolParams = extractToolParams(args);
            return handler.handle(userFile, toolParams, userId);
        } catch (IllegalArgumentException e) {
            log.warn("File tool '{}' error: {}", toolName, e.getMessage());
            return FileToolResponse.error(e.getMessage(), fileRef);
        } catch (Exception e) {
            log.error("File tool '{}' unexpected error", toolName, e);
            return FileToolResponse.error("Internal error: " + e.getMessage(), fileRef);
        }
    }

    /**
     * 调度文件工具请求（通过 FileToolController 直接调用，保留兼容）。
     */
    public FileToolResponse execute(HttpServletRequest request, FileToolRequest body) {
        String userId = AamTokenUtil.requireUserId(request);
        Map<String, Object> arguments = new LinkedHashMap<String, Object>();
        arguments.put("fileRef", body.getFileRef());
        if (body.getParams() != null) {
            arguments.putAll(body.getParams());
        }
        return execute(userId, body.getToolName(), arguments);
    }

    /**
     * 列出所有已注册的工具名称。
     */
    public List<String> getAvailableTools() {
        List<String> list = new ArrayList<String>(handlers.keySet());
        Collections.sort(list);
        return list;
    }

    /**
     * 检查工具是否已注册。
     */
    public boolean isRegistered(String toolName) {
        return handlers.containsKey(toolName);
    }

    // ================================================================
    // 文件管理基础实现（完整版在 task 6.x）
    // ================================================================

    private FileToolResponse listFiles(String userId) {
        List<UserFile> files = userFileMapper.findByUserId(userId);
        if (files.isEmpty()) {
            Map<String, Object> result = new LinkedHashMap<String, Object>();
            result.put("message", "No files found.");
            result.put("files", Collections.emptyList());
            return FileToolResponse.ok(result);
        }

        List<Map<String, Object>> fileList = new ArrayList<Map<String, Object>>();
        for (UserFile uf : files) {
            Map<String, Object> item = new LinkedHashMap<String, Object>();
            item.put("id", uf.getId());
            item.put("fileName", uf.getOriginalFileName());
            item.put("fileSize", uf.getFileSize());
            item.put("fileType", uf.getFileType());
            item.put("uploadTime", uf.getUploadTime() != null ? uf.getUploadTime().toString() : null);
            item.put("downloadUrl", uf.getDownloadUrl());
            item.put("parsed", fileParseService.isParseComplete(uf));
            fileList.add(item);
        }

        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("count", fileList.size());
        result.put("files", fileList);
        return FileToolResponse.ok(result, "user:" + userId);
    }

    private FileToolResponse deleteFile(UserFile userFile, String userId) {
        // 删除需要二次确认（by 壮实现时检查 params.confirmed）
        Map<String, Object> params = new LinkedHashMap<String, Object>();
        // 这里的二次确认逻辑由 LLM 在对话中引导，工具层仅检查标记
        params.put("message", "请确认是否删除文件 " + userFile.getOriginalFileName() + "？");
        params.put("requiresConfirmation", true);
        return FileToolResponse.ok(params, userFile.getOriginalFileName());
    }

    private FileToolResponse clearAllFiles(String userId) {
        List<UserFile> files = userFileMapper.findByUserId(userId);
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("fileCount", files.size());
        result.put("message", "请确认是否清空所有 " + files.size() + " 个文件？");
        result.put("requiresConfirmation", true);
        return FileToolResponse.ok(result, "user:" + userId);
    }

    private FileToolResponse fileDetail(UserFile userFile) {
        Map<String, Object> detail = new LinkedHashMap<String, Object>();
        detail.put("id", userFile.getId());
        detail.put("fileName", userFile.getOriginalFileName());
        detail.put("storageName", userFile.getFileName());
        detail.put("fileSize", userFile.getFileSize());
        detail.put("fileType", userFile.getFileType());
        detail.put("uploadTime", userFile.getUploadTime() != null ? userFile.getUploadTime().toString() : null);
        detail.put("downloadUrl", userFile.getDownloadUrl());
        detail.put("parsed", fileParseService.isParseComplete(userFile));

        if (fileParseService.isParseComplete(userFile)) {
            detail.put("parsedSummary", "Available (use system prompt for details)");
        }
        return FileToolResponse.ok(detail, userFile.getOriginalFileName());
    }

    // ================================================================
    // 内部辅助
    // ================================================================

    private boolean isManagementTool(String toolName) {
        return "file_list".equals(toolName) || "file_clear_all".equals(toolName);
    }

    /**
     * 从 arguments map 提取工具特定参数（排除 fileRef 等通用字段）。
     */
    private Map<String, Object> extractToolParams(Map<String, Object> arguments) {
        Map<String, Object> params = new LinkedHashMap<String, Object>();
        for (Map.Entry<String, Object> entry : arguments.entrySet()) {
            if ("fileRef".equals(entry.getKey())) continue;
            params.put(entry.getKey(), entry.getValue());
        }
        return params;
    }

    /**
     * 文件工具处理器函数式接口。
     * <p>
     * 后续任务实现时，实现此接口并注册到 {@link #registerHandler(String, ToolHandler)}。
     * </p>
     */
    public interface ToolHandler {
        /**
         * 处理文件工具调用。
         *
         * @param userFile 已解析的文件实体（管理类工具时为 null）
         * @param params   操作参数
         * @param userId   当前用户 ID
         * @return 操作结果
         * @throws Exception 处理异常
         */
        FileToolResponse handle(UserFile userFile, Map<String, Object> params, String userId) throws Exception;
    }
}
