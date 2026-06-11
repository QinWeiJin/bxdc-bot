package com.lobsterai.skillgateway.dto;

/**
 * 文件工具响应 DTO（5.1.2 标准出参规范）。
 * <p>
 * 所有文件操作 API 的统一响应格式。
 * 序列化为 JSON 后通过 Tool Result 机制回传给 agent-core / LLM。
 * </p>
 */
public class FileToolResponse {

    /** 操作是否成功 */
    private boolean success;

    /** 操作结果（任意 JSON 结构） */
    private Object output;

    /** 人类可读的消息（错误时填充） */
    private String message;

    /** 操作涉及的文件名（便于 LLM 识别上下文） */
    private String fileRef;

    public static FileToolResponse ok(Object output, String fileRef) {
        FileToolResponse r = new FileToolResponse();
        r.success = true;
        r.output = output;
        r.fileRef = fileRef;
        return r;
    }

    public static FileToolResponse ok(Object output) {
        return ok(output, null);
    }

    public static FileToolResponse error(String message, String fileRef) {
        FileToolResponse r = new FileToolResponse();
        r.success = false;
        r.message = message;
        r.fileRef = fileRef;
        return r;
    }

    public static FileToolResponse error(String message) {
        return error(message, null);
    }

    // ========== Getters & Setters ==========

    public boolean isSuccess() {
        return success;
    }

    public void setSuccess(boolean success) {
        this.success = success;
    }

    public Object getOutput() {
        return output;
    }

    public void setOutput(Object output) {
        this.output = output;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public String getFileRef() {
        return fileRef;
    }

    public void setFileRef(String fileRef) {
        this.fileRef = fileRef;
    }
}
