package com.lobsterai.skillgateway.dto;

import java.util.Map;

/**
 * 文件工具请求 DTO（5.1.2 标准入参规范）。
 * <p>
 * 所有文件操作 API 的统一请求格式：
 * <ul>
 *   <li>{@code toolName}: 工具名称，如 "word_read"、"txt_read"、"file_list"</li>
 *   <li>{@code fileId}: 文件 ID（优先使用），通过文件 ID 直接查询文件信息</li>
 *   <li>{@code fileRef}: 文件引用，可为原始文件名（如 "报表.xlsx"）或文件 ID，
 *       由 {@code FileRefResolver} 解析为 {@code UserFile} 实体</li>
 *   <li>{@code params}: 操作相关参数（如搜索关键字、行范围等），各工具自行定义</li>
 * </ul>
 * </p>
 * <p>优先级：fileId > fileRef。如果同时提供，优先使用 fileId。</p>
 */
public class FileToolRequest {

    private String toolName;
    private Long fileId;
    private String fileRef;
    private Map<String, Object> params;

    public String getToolName() {
        return toolName;
    }

    public void setToolName(String toolName) {
        this.toolName = toolName;
    }

    public Long getFileId() {
        return fileId;
    }

    public void setFileId(Long fileId) {
        this.fileId = fileId;
    }

    public String getFileRef() {
        return fileRef;
    }

    public void setFileRef(String fileRef) {
        this.fileRef = fileRef;
    }

    public Map<String, Object> getParams() {
        return params;
    }

    public void setParams(Map<String, Object> params) {
        this.params = params;
    }
}
