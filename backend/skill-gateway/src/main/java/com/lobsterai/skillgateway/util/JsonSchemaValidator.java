package com.lobsterai.skillgateway.util;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 极简 JSON Schema 校验工具 —— 仅实现 required + type 校验（含嵌套 object 与 array.items）。
 * 设计依据 docs/python-execution-skill-design.md §5.2 / design.md Decision 4。
 *
 * 支持的 type: "string" / "integer" / "number" / "boolean" / "object" / "array"
 * 数组元素类型约定在 "items.type"（仅一层，足够覆盖沙箱入参场景）。
 * 不支持 enum / pattern / format / minimum 等高级约束（按需后续 PR 扩展）。
 */
@Component
public final class JsonSchemaValidator {

    private final ObjectMapper objectMapper;

    public JsonSchemaValidator(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * 校验入参 data 是否符合 schema。
     *
     * @param schema JSON Schema 字符串（形如 {"type":"object","properties":{...},"required":[...]}）；
     *               null / 空字符串 / 非法 JSON 时视为 {} —— 不校验。
     * @param data   实际入参对象（已是 Map / List / 标量）；null 视为空 Map。
     * @throws IllegalArgumentException 校验失败（消息中包含字段名 / 期望 vs 实际类型）
     */
    public void validate(String schema, Object data) {
        Map<String, Object> schemaMap = parseSchemaObject(schema);
        if (schemaMap.isEmpty()) {
            return;
        }
        Object root = data == null ? Collections.emptyMap() : data;
        validateValue("", schemaMap, root);
    }

    /**
     * 把 schema 字符串解析为 Map<String, Object>，失败抛 IllegalArgumentException。
     * 仅接受顶层是 JSON object 的 schema（标准 JSON Schema 约定）。
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> parseSchemaObject(String schema) {
        if (schema == null || schema.trim().isEmpty()) {
            return Collections.emptyMap();
        }
        Object parsed;
        try {
            parsed = objectMapper.readValue(schema, Object.class);
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid JSON Schema: " + e.getMessage(), e);
        }
        if (parsed instanceof Map) {
            return (Map<String, Object>) parsed;
        }
        throw new IllegalArgumentException("JSON Schema root must be an object, got: "
                + (parsed == null ? "null" : parsed.getClass().getSimpleName()));
    }

    @SuppressWarnings("unchecked")
    private void validateValue(String path, Map<String, Object> schema, Object value) {
        // 1. type check
        String expectedType = (String) schema.get("type");
        if (expectedType != null) {
            checkType(path, expectedType, value);
        }

        // 2. object: required + properties
        if ("object".equals(expectedType) || (expectedType == null && value instanceof Map)) {
            Map<String, Object> obj = value instanceof Map ? (Map<String, Object>) value : Collections.emptyMap();
            List<String> required = (List<String>) schema.get("required");
            if (required != null) {
                for (String key : required) {
                    if (!obj.containsKey(key)) {
                        throw new IllegalArgumentException("Missing required field"
                                + (path.isEmpty() ? ": " + key : " '" + path + "." + key + "'"));
                    }
                }
            }
            Map<String, Object> properties = (Map<String, Object>) schema.get("properties");
            if (properties != null) {
                for (Map.Entry<String, Object> e : properties.entrySet()) {
                    String key = e.getKey();
                    if (obj.containsKey(key) && e.getValue() instanceof Map) {
                        String childPath = path.isEmpty() ? key : path + "." + key;
                        validateValue(childPath, (Map<String, Object>) e.getValue(), obj.get(key));
                    }
                }
            }
        }

        // 3. array: items
        if ("array".equals(expectedType) && value instanceof Collection) {
            Map<String, Object> items = (Map<String, Object>) schema.get("items");
            if (items != null) {
                int i = 0;
                for (Object item : (Collection<?>) value) {
                    validateValue(path + "[" + i + "]", items, item);
                    i++;
                }
            }
        }
    }

    private void checkType(String path, String expectedType, Object value) {
        if (value == null) {
            return; // null 由 required 把关；type 不查 null
        }
        boolean ok;
        switch (expectedType) {
            case "string":  ok = value instanceof String; break;
            case "integer": ok = value instanceof Integer || value instanceof Long
                                  || value instanceof Short || value instanceof Byte; break;
            case "number":  ok = value instanceof Number; break;
            case "boolean": ok = value instanceof Boolean; break;
            case "object":  ok = value instanceof Map; break;
            case "array":   ok = value instanceof Collection; break;
            case "null":    ok = false; break;
            default:        ok = true; // unknown type —— 放行（不阻塞）
        }
        if (!ok) {
            String field = path.isEmpty() ? "root" : path;
            String actual = value.getClass().getSimpleName();
            throw new IllegalArgumentException("Field '" + field + "' expected "
                    + expectedType + ", got " + actual);
        }
    }

    /**
     * 工具方法：把对象里的 keys 全列出来（用于错误信息）
     */
    @SuppressWarnings("unchecked")
    public static Set<String> keysOf(Object o) {
        if (o instanceof Map) {
            return new HashSet<String>((Set<String>) ((Map<?, ?>) o).keySet());
        }
        return Collections.emptySet();
    }
}
