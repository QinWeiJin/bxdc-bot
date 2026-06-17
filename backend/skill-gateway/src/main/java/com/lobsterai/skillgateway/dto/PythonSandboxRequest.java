package com.lobsterai.skillgateway.dto;

/**
 * Body for POST/PUT /api/python-sandbox[/...]。
 * serviceParams 必须是合法 JSON 对象（默认 '{}'），由 controller 校验。
 */
public class PythonSandboxRequest {
    /** 沙箱引用名，唯一。 */
    public String name;
    /** 完整 URL（含 scheme + host + path）。 */
    public String endpointUrl;
    /** POST 或 PUT；首版仅支持这两种。 */
    public String httpMethod;
    /** LLM 入参 JSON Schema 字符串（合法 JSON 对象）。null 时默认 '{}'。 */
    public String serviceParams;
    /** 0/1 整数；null 时默认 1。 */
    public Integer enabled;
    public String description;
}
