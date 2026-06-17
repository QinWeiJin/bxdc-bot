package com.lobsterai.skillgateway.controller;

import com.lobsterai.skillgateway.entity.Conversation;
import com.lobsterai.skillgateway.event.ConversationEventBus;
import com.lobsterai.skillgateway.metrics.CompactionMetrics;
import com.lobsterai.skillgateway.service.ConversationCompactService;
import com.lobsterai.skillgateway.service.ConversationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

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

    private static final Logger log = LoggerFactory.getLogger(ConversationController.class);

    private final ConversationService conversationService;
    private final ConversationEventBus eventBus;
    private final ConversationCompactService compactService;
    private final CompactionMetrics compactionMetrics;

    @Autowired
    public ConversationController(ConversationService conversationService,
                                  ConversationEventBus eventBus,
                                  ConversationCompactService compactService,
                                  CompactionMetrics compactionMetrics) {
        this.conversationService = conversationService;
        this.eventBus = eventBus;
        this.compactService = compactService;
        this.compactionMetrics = compactionMetrics;
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
        java.util.Map<String, Object> convBody = new java.util.LinkedHashMap<String, Object>();
        convBody.put("conversations", result);
        return ResponseEntity.ok(convBody);
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
                    .collect(java.util.stream.Collectors.toList())
                : Collections.emptyList();
        @SuppressWarnings("unchecked")
        List<Long> enabledFiles = body.get("enabled_files") instanceof List
                ? ((List<?>) body.get("enabled_files")).stream()
                    .filter(item -> item instanceof Number)
                    .map(item -> ((Number) item).longValue())
                    .collect(java.util.stream.Collectors.toList())
                : Collections.emptyList();

        Conversation conv = conversationService.create(userId, name, enabledSkills, enabledFiles);
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
                        .collect(java.util.stream.Collectors.toList())
                    : Collections.emptyList())
                : null;
        @SuppressWarnings("unchecked")
        List<Long> enabledFiles = body.containsKey("enabled_files")
                ? (body.get("enabled_files") instanceof List
                    ? ((List<?>) body.get("enabled_files")).stream()
                        .filter(item -> item instanceof Number)
                        .map(item -> ((Number) item).longValue())
                        .collect(java.util.stream.Collectors.toList())
                    : Collections.emptyList())
                : null;

        Conversation conv = conversationService.update(conversationId, userId, name, enabledSkills, enabledFiles);
        return ResponseEntity.ok(toConversationDto(conv));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Map<String, Object>> deleteConversation(
            @RequestHeader("X-User-Id") String userId,
            @PathVariable("id") String conversationId) {
        conversationService.delete(conversationId, userId);
        java.util.Map<String, Object> okBody = new java.util.LinkedHashMap<String, Object>();
        okBody.put("ok", true);
        return ResponseEntity.ok(okBody);
    }

    // ---- SSE: 对话级别实时事件订阅 ----

    /**
     * 订阅对话实时事件。当前主要发两类：
     * <ul>
     *   <li>{@code message_inserted} —— 新消息写入（payload 包含完整 message DTO）</li>
     *   <li>{@code message_updated} —— 消息字段更新（payload 包含完整 message DTO）</li>
     * </ul>
     * <p>
     * open spec: async-task-result-echo-to-chat —— 让异步任务完成时新消息自动出现在聊天流。
     * <p>
     * 注：EventSource 浏览器 API 不支持自定义 header，所以 userId 通过 {@code X-User-Id}
     * 请求头（fetch 客户端）或 {@code ?userId=} query 参数（EventSource）传入。query 路径下
     * 服务端仍按 X-User-Id 同样的方式做归属校验（{@link ConversationService#getById} 会抛 404）。
     */
    @GetMapping(value = "/{id}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamConversationEvents(
            @RequestHeader(value = "X-User-Id", required = false) String userIdHeader,
            @RequestParam(value = "userId", required = false) String userIdParam,
            @PathVariable("id") String conversationId) {
        String userId = userIdHeader != null ? userIdHeader : userIdParam;
        if (userId == null || userId.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Missing user identity");
        }
        // 校验对话归属（不在自己的对话上订阅会抛 404）
        conversationService.getById(conversationId, userId);
        return eventBus.register(conversationId);
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

    // ---- Context Compaction (internal, agent-core -> gateway) ----

    /**
     * 内部端点：agent-core 调一次拿"送 LLM 的最终 messages"。
     *
     * open spec: llm-context-window-summarization
     *
     * 鉴权：X-Internal-Token == env.INTERNAL_API_TOKEN
     * 输入：{ userId, messages: [...], model: "..." }
     * 输出：{ messages: [...], summaryApplied: bool, summarySource: "..." }
     *
     * 注意：此端点不要求 X-User-Id（agent-core 是内部服务，用 Internal-Token 鉴权）。
     * userId 用于读 user.llm_config（按 user 优先 → 系统默认 fallback）。
     * conversationId 仅做 cache key，不做归属校验（agent-core 已校验过）。
     */
    @PostMapping("/{id}/compact")
    public ResponseEntity<Map<String, Object>> compact(
            @RequestHeader(value = "X-Internal-Token", required = false) String internalToken,
            @PathVariable("id") String conversationId,
            @RequestBody Map<String, Object> body) {
        // 优先 system property（测试用），其次 env var
        String expected = System.getProperty("INTERNAL_API_TOKEN");
        if (expected == null || expected.isEmpty()) {
            expected = System.getenv("INTERNAL_API_TOKEN");
        }
        if (expected == null || expected.isEmpty()) {
            // 未配置 INTERNAL_API_TOKEN：开发环境兜底放行
            log.debug("[ConversationController.compact] INTERNAL_API_TOKEN not set, allowing (dev mode)");
        } else if (internalToken == null || !expected.equals(internalToken)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Collections.singletonMap("error", "invalid_internal_token"));
        }

        String userId = body.get("userId") instanceof String ? (String) body.get("userId") : null;
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> messages = body.get("messages") instanceof List
                ? (List<Map<String, Object>>) body.get("messages")
                : Collections.emptyList();

        ConversationCompactService.CompactResult result = compactService.compact(conversationId, userId, messages);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("messages", result.messages);
        response.put("summaryApplied", result.summaryApplied);
        response.put("summarySource", result.summarySource);
        return ResponseEntity.ok(response);
    }

    /**
     * 暴露压缩 metrics 快照（open spec: llm-context-window-summarization §9）
     * GET /api/conversations/compaction-metrics
     * 暂不加鉴权（内部端点，运维用）
     */
    @GetMapping("/compaction-metrics")
    public ResponseEntity<CompactionMetrics.Snapshot> compactionMetrics() {
        return ResponseEntity.ok(compactionMetrics.snapshot());
    }

    // ---- Helper ----

    private Map<String, Object> toConversationDto(Conversation conv) {
        Map<String, Object> dto = new LinkedHashMap<>();
        dto.put("id", conv.getId());
        dto.put("conversation_id", conv.getConversationId());
        dto.put("name", conv.getName());
        dto.put("enabled_skills", conv.getEnabledSkills());
        dto.put("enabled_files", conv.getEnabledFiles());
        dto.put("status", conv.getStatus());
        dto.put("is_published", conv.getIsPublished());
        dto.put("api_description", conv.getApiDescription());
        dto.put("created_at", conv.getCreatedAt() != null ? conv.getCreatedAt().toString() : null);
        dto.put("updated_at", conv.getUpdatedAt() != null ? conv.getUpdatedAt().toString() : null);
        return dto;
    }
}
