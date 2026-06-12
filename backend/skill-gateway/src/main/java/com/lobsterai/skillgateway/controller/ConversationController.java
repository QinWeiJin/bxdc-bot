package com.lobsterai.skillgateway.controller;

import com.lobsterai.skillgateway.entity.Conversation;
import com.lobsterai.skillgateway.service.ConversationService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * 对话会话 REST API。
 * <p>
 * 提供对话的创建、查询、更新、删除以及消息落库和分页历史加载能力。
 * 所有接口通过 {@code X-User-Id} 请求头标识当前用户。
 * </p>
 */
@RestController
@RequestMapping("/api/conversations")
public class ConversationController {

    private final ConversationService conversationService;

    public ConversationController(ConversationService conversationService) {
        this.conversationService = conversationService;
    }

    // ---- Conversation CRUD ----

    @GetMapping
    public ResponseEntity<Map<String, Object>> listConversations(
            @RequestHeader("X-User-Id") String userId) {
        List<Conversation> conversations = conversationService.listByUserId(userId);
        List<Map<String, Object>> result = new ArrayList<>();
        for (Conversation conv : conversations) {
            result.add(toConversationDto(conv));
        }
        return ResponseEntity.ok(Map.of("conversations", result));
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> createConversation(
            @RequestHeader("X-User-Id") String userId,
            @RequestBody Map<String, Object> body) {
        String name = body.get("name") instanceof String ? (String) body.get("name") : "";
        @SuppressWarnings("unchecked")
        List<Long> enabledSkills = body.get("enabled_skills") instanceof List
                ? ((List<?>) body.get("enabled_skills")).stream()
                    .filter(item -> item instanceof Number)
                    .map(item -> ((Number) item).longValue())
                    .toList()
                : Collections.emptyList();

        Conversation conv = conversationService.create(userId, name, enabledSkills);
        return ResponseEntity.status(HttpStatus.CREATED).body(toConversationDto(conv));
    }

    @GetMapping("/{id}")
    public ResponseEntity<Map<String, Object>> getConversation(
            @RequestHeader("X-User-Id") String userId,
            @PathVariable("id") String conversationId,
            @RequestParam(value = "cursor", required = false) String cursor,
            @RequestParam(value = "limit", required = false) Integer limit) {
        Conversation conv = conversationService.getById(conversationId, userId);
        Map<String, Object> messagesPage = conversationService.getMessages(conversationId, userId, cursor, limit);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("conversation", toConversationDto(conv));
        response.put("messages", messagesPage.get("messages"));
        response.put("hasMore", messagesPage.get("hasMore"));
        return ResponseEntity.ok(response);
    }

    @PutMapping("/{id}")
    public ResponseEntity<Map<String, Object>> updateConversation(
            @RequestHeader("X-User-Id") String userId,
            @PathVariable("id") String conversationId,
            @RequestBody Map<String, Object> body) {
        String name = body.get("name") instanceof String ? (String) body.get("name") : null;
        @SuppressWarnings("unchecked")
        List<Long> enabledSkills = body.containsKey("enabled_skills")
                ? (body.get("enabled_skills") instanceof List
                    ? ((List<?>) body.get("enabled_skills")).stream()
                        .filter(item -> item instanceof Number)
                        .map(item -> ((Number) item).longValue())
                        .toList()
                    : Collections.emptyList())
                : null;

        Conversation conv = conversationService.update(conversationId, userId, name, enabledSkills);
        return ResponseEntity.ok(toConversationDto(conv));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Map<String, Object>> deleteConversation(
            @RequestHeader("X-User-Id") String userId,
            @PathVariable("id") String conversationId) {
        conversationService.delete(conversationId, userId);
        return ResponseEntity.ok(Map.of("ok", true));
    }

    // ---- Messages ----

    @PostMapping("/{id}/messages")
    public ResponseEntity<Map<String, Object>> saveMessages(
            @RequestHeader("X-User-Id") String userId,
            @PathVariable("id") String conversationId,
            @RequestBody Map<String, Object> body) {
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> messages = body.get("messages") instanceof List
                ? (List<Map<String, Object>>) body.get("messages")
                : Collections.emptyList();

        Map<String, Object> result = conversationService.saveMessages(conversationId, userId, messages);
        return ResponseEntity.status(HttpStatus.CREATED).body(result);
    }

    // ---- Helper ----

    private Map<String, Object> toConversationDto(Conversation conv) {
        Map<String, Object> dto = new LinkedHashMap<>();
        dto.put("id", conv.getId());
        dto.put("conversation_id", conv.getConversationId());
        dto.put("name", conv.getName());
        dto.put("enabled_skills", conv.getEnabledSkills());
        dto.put("status", conv.getStatus());
        dto.put("is_published", conv.getIsPublished());
        dto.put("api_description", conv.getApiDescription());
        dto.put("created_at", conv.getCreatedAt() != null ? conv.getCreatedAt().toString() : null);
        dto.put("updated_at", conv.getUpdatedAt() != null ? conv.getUpdatedAt().toString() : null);
        return dto;
    }
}
