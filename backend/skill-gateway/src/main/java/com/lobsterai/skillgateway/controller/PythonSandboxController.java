package com.lobsterai.skillgateway.controller;

import com.lobsterai.skillgateway.dto.PythonSandboxRequest;
import com.lobsterai.skillgateway.dto.PythonSandboxView;
import com.lobsterai.skillgateway.entity.PythonSandbox;
import com.lobsterai.skillgateway.service.PythonSandboxService;
import com.lobsterai.skillgateway.util.JsonSchemaValidator;
import com.lobsterai.skillgateway.util.StringUtils;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/python-sandbox")
@CrossOrigin(origins = "*")
public class PythonSandboxController {

    private final PythonSandboxService pythonSandboxService;
    private final JsonSchemaValidator jsonSchemaValidator;

    public PythonSandboxController(PythonSandboxService pythonSandboxService,
                                   JsonSchemaValidator jsonSchemaValidator) {
        this.pythonSandboxService = pythonSandboxService;
        this.jsonSchemaValidator = jsonSchemaValidator;
    }

    /**
     * 列表：默认仅返回 enabled=true 行（与设计稿一致）。
     * 加上 ?all=true 且 X-User-Id=admin 时返回全量 —— admin 内部管理用。
     */
    @GetMapping
    public ResponseEntity<?> list(@RequestHeader Map<String, String> headers,
                                  @RequestParam(value = "all", required = false) String all) {
        try {
            List<PythonSandbox> rows;
            if ("true".equalsIgnoreCase(all) && isAdmin(headers)) {
                rows = pythonSandboxService.listAll();
            } else {
                rows = pythonSandboxService.listEnabled();
            }
            return ResponseEntity.ok(rows.stream().map(PythonSandboxController::toView)
                    .collect(Collectors.toList()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Collections.singletonMap("error", e.getMessage()));
        }
    }

    @GetMapping("/{name}")
    public ResponseEntity<?> getByName(@PathVariable String name) {
        try {
            PythonSandbox row = pythonSandboxService.getByNameOrThrow(name);
            return ResponseEntity.ok(toView(row));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Collections.singletonMap("error", e.getMessage()));
        }
    }

    @PostMapping
    public ResponseEntity<?> create(@RequestHeader Map<String, String> headers,
                                    @RequestBody PythonSandboxRequest req) {
        String denial = requireAdmin(headers);
        if (denial != null) return ResponseEntity.status(403).body(Collections.singletonMap("error", denial));
        try {
            validateServiceParams(req);
            PythonSandbox row = pythonSandboxService.create(req);
            return ResponseEntity.ok(toView(row));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Collections.singletonMap("error", e.getMessage()));
        }
    }

    @PutMapping("/{name}")
    public ResponseEntity<?> update(@RequestHeader Map<String, String> headers,
                                    @PathVariable String name,
                                    @RequestBody PythonSandboxRequest req) {
        String denial = requireAdmin(headers);
        if (denial != null) return ResponseEntity.status(403).body(Collections.singletonMap("error", denial));
        try {
            validateServiceParams(req);
            PythonSandbox row = pythonSandboxService.update(name, req);
            return ResponseEntity.ok(toView(row));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Collections.singletonMap("error", e.getMessage()));
        }
    }

    @DeleteMapping("/{name}")
    public ResponseEntity<?> delete(@RequestHeader Map<String, String> headers,
                                    @PathVariable String name) {
        String denial = requireAdmin(headers);
        if (denial != null) return ResponseEntity.status(403).body(Collections.singletonMap("error", denial));
        try {
            pythonSandboxService.delete(name);
            return ResponseEntity.ok().build();
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Collections.singletonMap("error", e.getMessage()));
        }
    }

    // ----- helpers -----

    private void validateServiceParams(PythonSandboxRequest req) {
        if (req == null) return;
        String sp = req.serviceParams;
        if (sp == null || StringUtils.isBlank(sp)) {
            req.serviceParams = "{}";
            return;
        }
        // 显式校验：必须是合法 JSON 对象（非数组 / 非标量）
        jsonSchemaValidator.parseSchemaObject(sp);
    }

    private static boolean isAdmin(Map<String, String> headers) {
        for (Map.Entry<String, String> e : headers.entrySet()) {
            if ("x-user-id".equalsIgnoreCase(e.getKey())) {
                return PythonSandboxService.SKILL_PLATFORM_ADMIN_USER_ID.equals(e.getValue());
            }
        }
        return false;
    }

    private static String requireAdmin(Map<String, String> headers) {
        for (Map.Entry<String, String> e : headers.entrySet()) {
            if ("x-user-id".equalsIgnoreCase(e.getKey())) {
                if (PythonSandboxService.SKILL_PLATFORM_ADMIN_USER_ID.equals(e.getValue())) {
                    return null;
                }
                return "Only platform admin (" + PythonSandboxService.SKILL_PLATFORM_ADMIN_USER_ID
                        + ") can modify python sandbox";
            }
        }
        return "X-User-Id header is required";
    }

    private static PythonSandboxView toView(PythonSandbox row) {
        PythonSandboxView v = new PythonSandboxView();
        v.id = row.getId();
        v.name = row.getName();
        v.endpointUrl = row.getEndpointUrl();
        v.httpMethod = row.getHttpMethod();
        v.serviceParams = row.getServiceParams();
        v.enabled = row.getEnabled();
        v.description = row.getDescription();
        v.createdAt = row.getCreatedAt();
        v.updatedAt = row.getUpdatedAt();
        return v;
    }
}
