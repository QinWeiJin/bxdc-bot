package com.lobsterai.skillgateway.controller;

import com.lobsterai.skillgateway.dto.FileToolRequest;
import com.lobsterai.skillgateway.dto.FileToolResponse;
import com.lobsterai.skillgateway.service.FileToolService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletRequest;
import java.util.*;

/**
 * 文件工具控制器（5.1.1）。
 * <p>
 * 统一注册所有文件操作 HTTP API 端点。
 * 所有文件操作通过单一调度入口 {@code POST /api/files/tools/execute}，
 * 由 {@link FileToolService} 按 toolName 分派到具体处理器。
 * </p>
 *
 * <h3>端点列表</h3>
 * <ul>
 *   <li>{@code GET  /api/files/tools} — 列出所有可用文件工具</li>
 *   <li>{@code POST /api/files/tools/execute} — 执行文件工具（统一调度入口）</li>
 *   <li>{@code GET  /api/files/health} — 健康检查</li>
 * </ul>
 *
 * <h3>请求格式（POST /api/files/tools/execute）</h3>
 * <pre>{@code
 * {
 *   "toolName": "file_list",         // 必填：工具名
 *   "fileRef": "报表.xlsx",           // 文件引用（管理类工具可选）
 *   "params": { "keyword": "..." }   // 可选：操作参数
 * }
 * }</pre>
 *
 * <h3>响应格式</h3>
 * <pre>{@code
 * {
 *   "success": true,
 *   "output": { ... },    // 操作结果
 *   "message": "...",     // 错误消息
 *   "fileRef": "..."      // 操作涉及的文件名
 * }
 * }</pre>
 */
@RestController
@RequestMapping("/api/files/tools")
public class FileToolController {

    private static final Logger log = LoggerFactory.getLogger(FileToolController.class);

    private final FileToolService fileToolService;

    public FileToolController(FileToolService fileToolService) {
        this.fileToolService = fileToolService;
    }

    /**
     * 获取所有可用的文件工具列表。
     * <p>
     * 供 agent-core 动态发现可用工具。
     * </p>
     */
    @GetMapping
    public List<Map<String, Object>> listTools() {
        List<Map<String, Object>> tools = new ArrayList<Map<String, Object>>();
        List<String> names = fileToolService.getAvailableTools();

        for (String name : names) {
            Map<String, Object> tool = new LinkedHashMap<String, Object>();
            tool.put("toolName", name);
            tool.put("category", getCategory(name));
            tool.put("description", getDescription(name));
            tools.add(tool);
        }
        return tools;
    }

    /**
     * 统一文件工具调度入口。
     * <p>
     * 代理 agent-core 调用：从 X-User-Id 头提取用户身份，
     * 解析 fileRef，路由到对应的文件工具处理器。
     * </p>
     */
    @PostMapping("/execute")
    public ResponseEntity<FileToolResponse> execute(
            HttpServletRequest request,
            @RequestBody FileToolRequest body
    ) {
        if (body == null) {
            return ResponseEntity.badRequest()
                    .body(FileToolResponse.error("Request body is required"));
        }
        log.debug("File tool request: toolName={}, fileRef={}", body.getToolName(), body.getFileRef());
        FileToolResponse result = fileToolService.execute(request, body);
        if (result.isSuccess()) {
            return ResponseEntity.ok(result);
        }
        // 用 400 返回错误：agent-core 的 formatToolError 会提取 error 字段
        return ResponseEntity.badRequest().body(result);
    }

    /**
     * 健康检查（不受 FileAccessConfig 拦截）。
     */
    @GetMapping("/health")
    public Map<String, Object> health() {
        Map<String, Object> status = new LinkedHashMap<String, Object>();
        status.put("status", "UP");
        status.put("availableTools", fileToolService.getAvailableTools().size());
        return status;
    }

    // ========== 工具元数据 ==========

    private String getCategory(String toolName) {
        if (toolName.startsWith("file_")) return "文件管理";
        if (toolName.startsWith("word_")) return "Word 操作";
        if (toolName.startsWith("txt_")) return "TXT 操作";
        if (toolName.startsWith("excel_")) return "Excel 操作";
        if (toolName.startsWith("md_")) return "Markdown 操作";
        return "其他";
    }

    private String getDescription(String toolName) {
        switch (toolName) {
            case "file_list":      return "列出用户所有已上传的文件";
            case "file_delete":    return "删除指定文件（需二次确认）";
            case "file_clear_all": return "清空用户所有文件（需二次确认）";
            case "file_detail":    return "查看文件详细信息（名称、大小、类型、解析摘要）";
            default:               return toolName + " 操作";
        }
    }
}
