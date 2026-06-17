package com.lobsterai.skillgateway.service;

import com.lobsterai.skillgateway.dto.PythonSandboxRequest;
import com.lobsterai.skillgateway.entity.PythonSandbox;
import com.lobsterai.skillgateway.mapper.PythonSandboxMapper;
import com.lobsterai.skillgateway.util.JsonSchemaValidator;
import com.lobsterai.skillgateway.util.StringUtils;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
public class PythonSandboxService {

    public static final String SKILL_PLATFORM_ADMIN_USER_ID = "890728";

    private final PythonSandboxMapper pythonSandboxMapper;
    private final JsonSchemaValidator jsonSchemaValidator;

    public PythonSandboxService(PythonSandboxMapper pythonSandboxMapper,
                                JsonSchemaValidator jsonSchemaValidator) {
        this.pythonSandboxMapper = pythonSandboxMapper;
        this.jsonSchemaValidator = jsonSchemaValidator;
    }

    public List<PythonSandbox> listEnabled() {
        return pythonSandboxMapper.findByEnabledTrueOrderByNameAsc();
    }

    public List<PythonSandbox> listAll() {
        return pythonSandboxMapper.selectList(null);
    }

    public PythonSandbox getByNameOrThrow(String name) {
        if (StringUtils.isBlank(name)) {
            throw new IllegalArgumentException("Python sandbox name is required");
        }
        return pythonSandboxMapper.findByName(name.trim())
                .orElseThrow(() -> new IllegalArgumentException(
                        "Unknown python sandbox: " + name));
    }

    public Optional<PythonSandbox> findByName(String name) {
        if (StringUtils.isBlank(name)) {
            return Optional.empty();
        }
        return pythonSandboxMapper.findByName(name.trim());
    }

    public PythonSandbox create(PythonSandboxRequest req) {
        PythonSandbox row = toEntity(req);
        requireWellFormed(row, true);
        if (pythonSandboxMapper.findByName(row.getName()).isPresent()) {
            throw new IllegalArgumentException(
                    "Python sandbox name already exists: " + row.getName());
        }
        pythonSandboxMapper.insert(row);
        return row;
    }

    public PythonSandbox update(String name, PythonSandboxRequest req) {
        PythonSandbox existing = getByNameOrThrow(name);
        if (req.name != null && !req.name.trim().equals(existing.getName())) {
            if (pythonSandboxMapper.findByName(req.name.trim()).isPresent()) {
                throw new IllegalArgumentException(
                        "Python sandbox name already exists: " + req.name);
            }
            existing.setName(req.name.trim());
        }
        if (req.endpointUrl != null) {
            existing.setEndpointUrl(req.endpointUrl.trim());
        }
        if (req.httpMethod != null) {
            existing.setHttpMethod(req.httpMethod.trim().toUpperCase());
        }
        if (req.serviceParams != null) {
            // validate JSON object (parseSchemaObject throws on failure)
            jsonSchemaValidator.parseSchemaObject(req.serviceParams);
            existing.setServiceParams(req.serviceParams);
        }
        if (req.enabled != null) {
            existing.setEnabled(req.enabled);
        }
        if (req.description != null) {
            existing.setDescription(req.description);
        }
        requireWellFormed(existing, false);
        pythonSandboxMapper.updateById(existing);
        return existing;
    }

    public void delete(String name) {
        PythonSandbox existing = getByNameOrThrow(name);
        pythonSandboxMapper.deleteById(existing.getId());
    }

    /**
     * 把 sandbox.service_params (JSON Schema 字符串) 解析为 Map<String, Object>。
     * 失败抛 IllegalArgumentException，调用方需在出站前 catch。
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> parseServiceParams(PythonSandbox sandbox) {
        if (sandbox == null) {
            return Collections.emptyMap();
        }
        return jsonSchemaValidator.parseSchemaObject(sandbox.getServiceParams());
    }

    private static PythonSandbox toEntity(PythonSandboxRequest req) {
        PythonSandbox row = new PythonSandbox();
        if (req.name != null) row.setName(req.name.trim());
        if (req.endpointUrl != null) row.setEndpointUrl(req.endpointUrl.trim());
        if (req.httpMethod != null) row.setHttpMethod(req.httpMethod.trim().toUpperCase());
        // service_params: 缺省给 '{}'，避免 service 层空判断
        row.setServiceParams(req.serviceParams == null || StringUtils.isBlank(req.serviceParams)
                ? "{}" : req.serviceParams);
        row.setEnabled(req.enabled == null ? 1 : req.enabled);
        row.setDescription(req.description);
        return row;
    }

    private void requireWellFormed(PythonSandbox row, boolean forCreate) {
        if (StringUtils.isBlank(row.getName())) {
            throw new IllegalArgumentException("name is required");
        }
        if (row.getName().trim().length() > 64) {
            throw new IllegalArgumentException("name length must be <= 64");
        }
        if (StringUtils.isBlank(row.getEndpointUrl())) {
            throw new IllegalArgumentException("endpointUrl is required");
        }
        if (row.getHttpMethod() == null
                || !("POST".equals(row.getHttpMethod()) || "PUT".equals(row.getHttpMethod()))) {
            throw new IllegalArgumentException("httpMethod must be POST or PUT (got: "
                    + row.getHttpMethod() + ")");
        }
        if (row.getServiceParams() == null) {
            row.setServiceParams("{}");
        }
        if (forCreate) {
            // 写入前再做一次 JSON 校验（DTO 已在 controller 校验过；这里兜底）
            jsonSchemaValidator.parseSchemaObject(row.getServiceParams());
        }
        if (row.getEnabled() == null) {
            row.setEnabled(1);
        }
    }
}
