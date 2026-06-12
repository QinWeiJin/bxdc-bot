package com.lobsterai.skillgateway.controller;

import com.lobsterai.skillgateway.service.ConversationApiService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * 对话 API 发布与调用控制器。
 * <p>
 * 提供对话发布为 API、API Key 管理、外部 API 调用和调用记录查询能力。
 * 前端接口通过 {@code X-User-Id} 请求头标识当前用户。
 * 外部 API 调用通过请求体中的 {@code apiKey} 字段认证。
 * </p>
 */
@RestController
public class ConversationApiController {

    private final ConversationApiService apiService;

    public ConversationApiController(ConversationApiService apiService) {
        this.apiService = apiService;
    }

    // ---- Publish ----

    @PutMapping("/api/conversations/{id}/publish")
    public ResponseEntity<Map<String, Object>> publish(
            @RequestHeader("X-User-Id") String userId,
            @PathVariable("id") String conversationId,
            @RequestBody Map<String, Object> body) {
        String apiDescription = body.get("apiDescription") instanceof String
                ? (String) body.get("apiDescription") : "";
        Map<String, Object> result = apiService.publish(conversationId, userId, apiDescription);
        return ResponseEntity.ok(result);
    }

    // ---- External API Call ----

    @PostMapping("/api/agent-chat")
    public ResponseEntity<Map<String, Object>> agentChat(@RequestBody Map<String, Object> body) {
        String apiKey = body.get("apiKey") instanceof String ? (String) body.get("apiKey") : "";
        String instruction = body.get("instruction") instanceof String ? (String) body.get("instruction") : "";
        String callerId = body.get("callerId") instanceof String ? (String) body.get("callerId") : null;

        if (apiKey.isEmpty()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Collections.singletonMap("error", "Invalid API key"));
        }

        Map<String, Object> result = apiService.agentChat(apiKey, instruction, callerId);
        return ResponseEntity.ok(result);
    }

    // ---- Call Logs ----

    @GetMapping("/api/conversations/{id}/call-logs")
    public ResponseEntity<Map<String, Object>> getCallLogs(
            @RequestHeader("X-User-Id") String userId,
            @PathVariable("id") String conversationId,
            @RequestParam(value = "page", defaultValue = "1") int page,
            @RequestParam(value = "size", defaultValue = "20") int size) {
        Map<String, Object> result = apiService.getCallLogs(conversationId, userId, page, size);
        return ResponseEntity.ok(result);
    }

    // ---- API Key Management ----

    @GetMapping("/api/conversations/{id}/api-key")
    public ResponseEntity<Map<String, Object>> getApiKey(
            @RequestHeader("X-User-Id") String userId,
            @PathVariable("id") String conversationId) {
        Map<String, Object> result = apiService.getApiKey(conversationId, userId);
        return ResponseEntity.ok(result);
    }

    @PutMapping("/api/conversations/{id}/regenerate-api-key")
    public ResponseEntity<Map<String, Object>> regenerateApiKey(
            @RequestHeader("X-User-Id") String userId,
            @PathVariable("id") String conversationId) {
        Map<String, Object> result = apiService.regenerateApiKey(conversationId, userId);
        return ResponseEntity.ok(result);
    }
}
