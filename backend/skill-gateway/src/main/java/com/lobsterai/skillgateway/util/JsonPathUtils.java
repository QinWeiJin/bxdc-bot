package com.lobsterai.skillgateway.util;

import java.util.List;
import java.util.Map;

/**
 * Simple dot-notation JSON path utility (with optional {@code $} prefix for JsonPath-style paths).
 * Shared by async polling and enum-source proxy.
 *
 * <p>Supported syntax:</p>
 * <ul>
 *   <li>{@code "status"} — direct field</li>
 *   <li>{@code "data.status"} — nested field via dot</li>
 *   <li>{@code "$.status"} / {@code "$.data.status"} — JsonPath-style with optional leading {@code $}</li>
 *   <li>{@code "items.0.name"} — array index by integer segment</li>
 * </ul>
 */
public final class JsonPathUtils {

    private JsonPathUtils() {}

    /**
     * Navigate a parsed JSON tree by dot-separated path.
     * Returns the raw Object at that path, or null.
     */
    @SuppressWarnings("unchecked")
    public static Object extractValueByPath(Object obj, String path) {
        if (obj == null || path == null || StringUtils.isBlank(path)) return null;
        String[] segments = path.split("\\.");
        Object current = obj;
        // 兼容 JsonPath 风格的 $ 前缀：$.status → segments=["","$","status"]
        // 跳过第一个空段和 "$" 段（如果是的话），保留后续真正的路径段
        int startIdx = 0;
        if (segments.length > 0 && segments[0].isEmpty()) {
            startIdx = 1;
        }
        if (startIdx < segments.length && "$".equals(segments[startIdx])) {
            startIdx++;
        }
        for (int i = startIdx; i < segments.length; i++) {
            String segment = segments[i];
            if (current == null) return null;
            if (current instanceof Map) {
                current = ((Map<String, Object>) current).get(segment);
            } else if (current instanceof List) {
                try {
                    int index = Integer.parseInt(segment);
                    List<Object> list = (List<Object>) current;
                    if (index >= 0 && index < list.size()) {
                        current = list.get(index);
                    } else {
                        return null;
                    }
                } catch (NumberFormatException e) {
                    return null;
                }
            } else {
                return null;
            }
        }
        return current;
    }
}
