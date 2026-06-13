package com.lobsterai.skillgateway.service;

import com.lobsterai.skillgateway.dto.ExcelOperationResult;
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
    private final ExcelToolService excelToolService;
    private final Map<String, ToolHandler> handlers = new ConcurrentHashMap<String, ToolHandler>();

    public FileToolService(FileRefResolver fileRefResolver,
                           UserFileMapper userFileMapper,
                           FileParseService fileParseService,
                           ExcelToolService excelToolService) {
        this.fileRefResolver = fileRefResolver;
        this.userFileMapper = userFileMapper;
        this.fileParseService = fileParseService;
        this.excelToolService = excelToolService;
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

        // ===== Excel 操作（task 5.2）=====
        handlers.put("excel_read", new ToolHandler() {
            @Override
            public FileToolResponse handle(UserFile userFile, Map<String, Object> params, String userId) {
                return excelToolResultToResponse(excelToolService.read(userFile.getId(), userId));
            }
        });
        handlers.put("excel_filter", new ToolHandler() {
            @Override
            public FileToolResponse handle(UserFile userFile, Map<String, Object> params, String userId) {
                List<ExcelOperationResult.FilterCriteria> criteria = parseFilterCriteria(params);
                return excelToolResultToResponse(excelToolService.filter(userFile.getId(), userId, criteria));
            }
        });
        handlers.put("excel_sort", new ToolHandler() {
            @Override
            public FileToolResponse handle(UserFile userFile, Map<String, Object> params, String userId) {
                List<ExcelOperationResult.SortSpec> sortSpecs = parseSortSpecs(params);
                return excelToolResultToResponse(excelToolService.sort(userFile.getId(), userId, sortSpecs));
            }
        });
        handlers.put("excel_aggregate", new ToolHandler() {
            @Override
            public FileToolResponse handle(UserFile userFile, Map<String, Object> params, String userId) {
                String groupBy = (String) params.get("groupBy");
                List<ExcelOperationResult.AggregationSpec> aggs = parseAggregationSpecs(params);
                return excelToolResultToResponse(excelToolService.aggregate(userFile.getId(), userId, groupBy, aggs));
            }
        });
        handlers.put("excel_pivot", new ToolHandler() {
            @Override
            public FileToolResponse handle(UserFile userFile, Map<String, Object> params, String userId) {
                String rowField = (String) params.get("rowField");
                String colField = (String) params.get("colField");
                String valueField = (String) params.get("valueField");
                return excelToolResultToResponse(excelToolService.pivot(userFile.getId(), userId, rowField, colField, valueField));
            }
        });
        handlers.put("excel_calculate", new ToolHandler() {
            @Override
            public FileToolResponse handle(UserFile userFile, Map<String, Object> params, String userId) {
                List<ExcelOperationResult.CalculateExpression> exprs = parseExpressions(params);
                return excelToolResultToResponse(excelToolService.calculate(userFile.getId(), userId, exprs));
            }
        });
        handlers.put("excel_select_columns", new ToolHandler() {
            @Override
            public FileToolResponse handle(UserFile userFile, Map<String, Object> params, String userId) {
                @SuppressWarnings("unchecked")
                List<String> columns = (List<String>) params.get("columns");
                return excelToolResultToResponse(excelToolService.selectColumns(userFile.getId(), userId, columns));
            }
        });
        handlers.put("excel_clean", new ToolHandler() {
            @Override
            public FileToolResponse handle(UserFile userFile, Map<String, Object> params, String userId) {
                String strategy = (String) params.get("strategy");
                return excelToolResultToResponse(excelToolService.clean(userFile.getId(), userId, strategy));
            }
        });
        handlers.put("excel_convert_format", new ToolHandler() {
            @Override
            public FileToolResponse handle(UserFile userFile, Map<String, Object> params, String userId) {
                String format = (String) params.get("targetFormat");
                return excelToolResultToResponse(excelToolService.convertFormat(userFile.getId(), userId, format));
            }
        });
        handlers.put("excel_validate", new ToolHandler() {
            @Override
            public FileToolResponse handle(UserFile userFile, Map<String, Object> params, String userId) {
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> rules = (List<Map<String, Object>>) params.get("rules");
                return excelToolResultToResponse(excelToolService.validate(userFile.getId(), userId, rules));
            }
        });
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
        Long fileId = args.get("fileId") instanceof Number ? ((Number) args.get("fileId")).longValue() : null;

        try {
            UserFile userFile = null;
            if (!isManagementTool(toolName)) {
                // 优先使用 fileId
                if (fileId != null) {
                    userFile = userFileMapper.selectById(fileId);
                    if (userFile == null) {
                        return FileToolResponse.error("File not found by id: " + fileId);
                    }
                    // 验证文件归属
                    if (!userId.equals(userFile.getUserId())) {
                        return FileToolResponse.error("Access denied: file does not belong to current user");
                    }
                } else if (fileRef != null && !fileRef.trim().isEmpty()) {
                    userFile = fileRefResolver.resolve(userId, fileRef);
                } else if (!isOptionalFileIdTool(toolName)) {
                    // 非 OptionalFileId 工具必须提供 fileId 或 fileRef
                    return FileToolResponse.error("fileId or fileRef is required for tool: " + toolName);
                }
                // OptionalFileId 工具允许 fileId 和 fileRef 都为空，userFile 保持 null
            }
            // 从 arguments 提取工具特定 params（排除 fileId 和 fileRef）
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
        // 优先使用 fileId
        if (body.getFileId() != null) {
            arguments.put("fileId", body.getFileId());
        }
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
     * 判断工具是否允许不传 fileId（fileId 可选）。
     * 这些工具在没有 fileId 时会创建新文件。
     */
    private boolean isOptionalFileIdTool(String toolName) {
        return "excel_write".equals(toolName);
    }

    /**
     * 从 arguments map 提取工具特定参数（排除 fileId、fileRef 等通用字段）。
     */
    private Map<String, Object> extractToolParams(Map<String, Object> arguments) {
        Map<String, Object> params = new LinkedHashMap<String, Object>();
        for (Map.Entry<String, Object> entry : arguments.entrySet()) {
            if ("fileId".equals(entry.getKey())) continue;
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

    // ================================================================
    // Excel 工具辅助方法
    // ================================================================

    private FileToolResponse excelToolResultToResponse(ExcelOperationResult result) {
        Map<String, Object> data = new LinkedHashMap<String, Object>();
        data.put("success", result.isSuccess());
        data.put("operation", result.getOperation());
        
        if (result.getResult() != null) {
            data.put("columns", result.getResult().getColumns());
            data.put("rows", result.getResult().getRows());
            data.put("rowCount", result.getRowCount());
            data.put("colCount", result.getColCount());
        }
        
        if (result.getDownloadUrl() != null) {
            data.put("downloadUrl", result.getDownloadUrl());
        }
        
        if (result.getMessage() != null) {
            data.put("message", result.getMessage());
        }
        
        return FileToolResponse.ok(data, "excel_tool");
    }

    @SuppressWarnings("unchecked")
    private List<ExcelOperationResult.FilterCriteria> parseFilterCriteria(Map<String, Object> params) {
        List<Map<String, Object>> criteriaList = (List<Map<String, Object>>) params.get("criteria");
        List<ExcelOperationResult.FilterCriteria> criteria = new ArrayList<ExcelOperationResult.FilterCriteria>();
        for (Map<String, Object> c : criteriaList) {
            criteria.add(new ExcelOperationResult.FilterCriteria(
                    (String) c.get("column"),
                    (String) c.get("operator"),
                    c.get("value")));
        }
        return criteria;
    }

    @SuppressWarnings("unchecked")
    private List<ExcelOperationResult.SortSpec> parseSortSpecs(Map<String, Object> params) {
        List<Map<String, Object>> sortList = (List<Map<String, Object>>) params.get("sortSpecs");
        List<ExcelOperationResult.SortSpec> sortSpecs = new ArrayList<ExcelOperationResult.SortSpec>();
        for (Map<String, Object> s : sortList) {
            sortSpecs.add(new ExcelOperationResult.SortSpec(
                    (String) s.get("column"),
                    (String) s.get("order")));
        }
        return sortSpecs;
    }

    @SuppressWarnings("unchecked")
    private List<ExcelOperationResult.AggregationSpec> parseAggregationSpecs(Map<String, Object> params) {
        List<Map<String, Object>> aggList = (List<Map<String, Object>>) params.get("aggregations");
        List<ExcelOperationResult.AggregationSpec> aggs = new ArrayList<ExcelOperationResult.AggregationSpec>();
        for (Map<String, Object> a : aggList) {
            aggs.add(new ExcelOperationResult.AggregationSpec(
                    (String) a.get("column"),
                    (String) a.get("function")));
        }
        return aggs;
    }

    @SuppressWarnings("unchecked")
    private List<ExcelOperationResult.CalculateExpression> parseExpressions(Map<String, Object> params) {
        List<Map<String, Object>> exprList = (List<Map<String, Object>>) params.get("expressions");
        List<ExcelOperationResult.CalculateExpression> exprs = new ArrayList<ExcelOperationResult.CalculateExpression>();
        for (Map<String, Object> e : exprList) {
            exprs.add(new ExcelOperationResult.CalculateExpression(
                    (String) e.get("newColumn"),
                    (String) e.get("expression")));
        }
        return exprs;
    }
}
