package com.lobsterai.skillgateway.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.LocalDateTime;

/**
 * Read DTO for /api/python-sandbox responses。首版字段与实体一致。
 * 若未来加敏感字段（如 auth_token），必须在此 DTO 显式过滤。
 */
public class PythonSandboxView {
    public Long id;
    public String name;
    public String endpointUrl;
    public String httpMethod;
    @JsonProperty("serviceParams")
    public String serviceParams;
    public Integer enabled;
    public String description;
    public LocalDateTime createdAt;
    public LocalDateTime updatedAt;
}
