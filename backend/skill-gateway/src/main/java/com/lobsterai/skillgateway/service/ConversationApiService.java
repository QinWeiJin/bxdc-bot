package com.lobsterai.skillgateway.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lobsterai.skillgateway.entity.ApiCallLog;
import com.lobsterai.skillgateway.entity.Conversation;
import com.lobsterai.skillgateway.entity.ConversationMessage;
import com.lobsterai.skillgateway.mapper.ApiCallLogMapper;
import com.lobsterai.skillgateway.mapper.ConversationMapper;
import com.lobsterai.skillgateway.mapper.ConversationMessageMapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.*;

@Service
public class ConversationApiService {

    private static final Logger log = LoggerFactory.getLogger(ConversationApiService.class);
    private static final int HISTORY_LIMIT = 10;
    private static final int AGENT_TIMEOUT_MS = 120_000;

    private final ConversationMapper conversationMapper;
    private final ConversationMessageMapper messageMapper;
    private final ApiCallLogMapper apiCallLogMapper;
    private final ConversationService conversationService;
    private final ObjectMapper objectMapper;
    private final RestTemplate gatewayRestTemplate;

    @Value("${agent.core.url:http://localhost:3000}")
    private String agentCoreUrl;

    public ConversationApiService(ConversationMapper conversationMapper,
                                  ConversationMessageMapper messageMapper,
                                  ApiCallLogMapper apiCallLogMapper,
                                  ConversationService conversationService,
                                  ObjectMapper objectMapper,
                                  RestTemplate gatewayRestTemplate) {
        this.conversationMapper = conversationMapper;
        this.messageMapper = messageMapper;
        this.apiCallLogMapper = apiCallLogMapper;
        this.conversationService = conversationService;
        this.objectMapper = objectMapper;
        this.gatewayRestTemplate = gatewayRestTemplate;
    }

    // ---- Publish ----

    @Transactional
    public Map<String, Object> publish(String conversationId, String userId, String apiDescription) {
        Conversation conv = conversationService.getById(conversationId, userId);

        if (apiDescription == null || apiDescription.trim().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "API description is required");
        }
        if (Boolean.TRUE.equals(conv.getIsPublished())) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Conversation is already published");
        }

        String apiKey = "c_" + UUID.randomUUID().toString().replace("-", "");
        String apiKeyHash = sha256Hex(apiKey);

        conv.setIsPublished(true);
        conv.setApiDescription(apiDescription.trim());
        conv.setApiKey(apiKey);
        conv.setApiKeyHash(apiKeyHash);
        conv.setUpdatedAt(LocalDateTime.now());
        conversationMapper.updateById(conv);

        Map<String, Object> dto = toConversationDto(conv);
        dto.put("apiKey", apiKey);
        return dto;
    }

    // ---- Agent Chat ----

    public Map<String, Object> agentChat(String apiKey, String instruction, String callerId) {
        if (instruction == null || instruction.trim().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "instruction is required");
        }

        // Authenticate by apiKey
        String apiKeyHash = sha256Hex(apiKey);
        List<Conversation> convs = conversationMapper.selectByApiKeyHash(apiKeyHash);
        if (convs.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid API key");
        }
        Conversation conv = convs.get(0);
        if (!Boolean.TRUE.equals(conv.getIsPublished())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Conversation is not published");
        }

        long startMs = System.currentTimeMillis();
        String status = "running";
        String reply = null;
        int toolCallCount = 0;
        String errorMessage = null;

        // Prepare call log
        ApiCallLog callLog = new ApiCallLog();
        callLog.setConversationId(conv.getConversationId());
        callLog.setUserId(conv.getUserId());
        callLog.setCallerId(callerId);
        callLog.setInstruction(instruction);
        callLog.setStatus(status);
        callLog.setCreatedAt(LocalDateTime.now());
        apiCallLogMapper.insert(callLog);

        try {
            // Build history with api_description as system message
            List<Map<String, String>> history = buildHistory(conv);

            // Build agent-core request body
            Map<String, Object> agentRequest = new LinkedHashMap<>();
            agentRequest.put("instruction", instruction);
            agentRequest.put("history", history);
            agentRequest.put("conversationId", conv.getConversationId());
            agentRequest.put("enabledSkillIds", parseEnabledSkills(conv.getEnabledSkills()));

            // Call agent-core via SSE
            String url = agentCoreUrl + "/agent/run";
            Map<String, Object> sseResult = callAgentCoreSSE(url, agentRequest);
            reply = (String) sseResult.get("reply");
            toolCallCount = (int) sseResult.getOrDefault("toolCalls", 0);

            status = "success";
        } catch (ResponseStatusException e) {
            status = "error";
            errorMessage = "Agent service error: " + e.getMessage();
            throw e;
        } catch (Exception e) {
            status = "error";
            errorMessage = "Agent service unavailable: " + e.getMessage();
            log.error("[agent-chat] agent-core call failed for conversation={}", conv.getConversationId(), e);
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Agent service unavailable");
        } finally {
            // Update call log
            long durationMs = System.currentTimeMillis() - startMs;
            callLog.setStatus(status);
            callLog.setReply(reply);
            callLog.setToolCallCount(toolCallCount);
            callLog.setDurationMs((int) Math.min(durationMs, Integer.MAX_VALUE));
            callLog.setErrorMessage(errorMessage);
            apiCallLogMapper.updateById(callLog);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("conversationId", conv.getConversationId());
        result.put("reply", reply);
        result.put("toolCalls", toolCallCount);
        result.put("durationMs", (int) (System.currentTimeMillis() - startMs));
        return result;
    }

    private List<Map<String, String>> buildHistory(Conversation conv) {
        List<Map<String, String>> history = new ArrayList<>();

        // First message: system context with api_description
        Map<String, String> systemMsg = new LinkedHashMap<>();
        systemMsg.put("role", "system");
        systemMsg.put("content", "你是一个已发布为 API 的助手，提供以下服务：\n"
                + conv.getApiDescription()
                + "\n\n请严格按照上述描述提供服务，不要偏离描述的职责范围。"
                + "如果用户请求超出上述范围，请礼貌告知用户该 API 不支持此功能。");
        history.add(systemMsg);

        // Add recent conversation messages
        List<ConversationMessage> messages = messageMapper.selectByConversationIdCursor(
                conv.getConversationId(), null, HISTORY_LIMIT);
        // Messages come back DESC, reverse to chronological order
        Collections.reverse(messages);
        for (ConversationMessage msg : messages) {
            Map<String, String> m = new LinkedHashMap<>();
            m.put("role", msg.getRole());
            m.put("content", msg.getContent() != null ? msg.getContent() : "");
            history.add(m);
        }

        return history;
    }

    @SuppressWarnings("unchecked")
    private List<Long> parseEnabledSkills(String enabledSkillsJson) {
        if (enabledSkillsJson == null || enabledSkillsJson.isEmpty()) {
            return Collections.emptyList();
        }
        try {
            return objectMapper.readValue(enabledSkillsJson, new TypeReference<List<Long>>() {});
        } catch (Exception e) {
            log.warn("Failed to parse enabledSkills: {}", enabledSkillsJson, e);
            return Collections.emptyList();
        }
    }

    private Map<String, Object> callAgentCoreSSE(String url, Map<String, Object> requestBody) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(10_000);
        factory.setReadTimeout(AGENT_TIMEOUT_MS);
        RestTemplate sseTemplate = new RestTemplate(factory);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Agent-Token", System.getenv().getOrDefault("JAVA_GATEWAY_TOKEN", "your-secure-token-here"));
        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(requestBody, headers);

        StringBuilder replyBuilder = new StringBuilder();
        int[] toolCallCount = new int[1];

        try {
            sseTemplate.execute(url, HttpMethod.POST,
                    requestCallback -> {
                        requestCallback.getHeaders().setContentType(MediaType.APPLICATION_JSON);
                        requestCallback.getHeaders().set("X-Agent-Token",
                                System.getenv().getOrDefault("JAVA_GATEWAY_TOKEN", "your-secure-token-here"));
                        objectMapper.writeValue(requestCallback.getBody(), requestBody);
                    },
                    clientHttpResponse -> {
                        try (BufferedReader reader = new BufferedReader(
                                new InputStreamReader(clientHttpResponse.getBody(), StandardCharsets.UTF_8))) {
                            String line;
                            while ((line = reader.readLine()) != null) {
                                if (line.isEmpty()) continue;
                                if (line.startsWith("data: ")) {
                                    String data = line.substring(6);
                                    if ("[DONE]".equals(data)) break;
                                    try {
                                        Map<String, Object> event = objectMapper.readValue(data, Map.class);
                                        String eventType = (String) event.getOrDefault("type", "");
                                        if ("agent_message".equals(eventType)) {
                                            Object content = event.get("content");
                                            if (content != null) {
                                                replyBuilder.append(content);
                                            }
                                        } else if ("agent_update".equals(eventType)) {
                                            // Streaming agent state update – extract nested content
                                            Object agent = event.get("agent");
                                            if (agent instanceof Map) {
                                                Object content = ((Map<?, ?>) agent).get("content");
                                                if (content != null) {
                                                    replyBuilder.setLength(0);
                                                    replyBuilder.append(content);
                                                }
                                            }
                                        } else if ("tool_status".equals(eventType)) {
                                            toolCallCount[0]++;
                                        } else if ("confirmation_request".equals(eventType)) {
                                            // Auto-deny confirmation in API context
                                            String taskId = (String) event.get("taskId");
                                            if (taskId != null) {
                                                denyConfirmation(taskId);
                                            }
                                        } else if (eventType.isEmpty()) {
                                            // Final message chunk (role + content, no type field)
                                            String role = (String) event.get("role");
                                            if ("assistant".equals(role)) {
                                                Object content = event.get("content");
                                                if (content != null) {
                                                    replyBuilder.setLength(0);
                                                    replyBuilder.append(content);
                                                }
                                            }
                                        }
                                    } catch (Exception e) {
                                        log.warn("[agent-chat] failed to parse SSE event: {}", data, e);
                                    }
                                }
                            }
                        }
                        return null;
                    });
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "Agent service call failed: " + e.getMessage(), e);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("reply", replyBuilder.toString());
        result.put("toolCalls", toolCallCount[0]);
        return result;
    }

    private void denyConfirmation(String taskId) {
        try {
            String url = agentCoreUrl + "/agent/confirm";
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("taskId", taskId);
            body.put("confirmed", false);
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("X-Agent-Token",
                    System.getenv().getOrDefault("JAVA_GATEWAY_TOKEN", "your-secure-token-here"));
            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, headers);
            gatewayRestTemplate.postForEntity(url, entity, String.class);
        } catch (Exception e) {
            log.warn("[agent-chat] failed to deny confirmation taskId={}: {}", taskId, e.getMessage());
        }
    }

    // ---- Call Logs ----

    public Map<String, Object> getCallLogs(String conversationId, String userId, int page, int size) {
        conversationService.getById(conversationId, userId);
        Page<ApiCallLog> paged = apiCallLogMapper.selectByConversationIdPaged(conversationId, page, size);

        List<Map<String, Object>> logs = new ArrayList<>();
        for (ApiCallLog log : paged.getRecords()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", log.getId());
            item.put("callerId", log.getCallerId());
            item.put("instruction", log.getInstruction());
            item.put("reply", log.getReply());
            item.put("toolCallCount", log.getToolCallCount());
            item.put("durationMs", log.getDurationMs());
            item.put("status", log.getStatus());
            item.put("errorMessage", log.getErrorMessage());
            item.put("createdAt", log.getCreatedAt() != null ? log.getCreatedAt().toString() : null);
            logs.add(item);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("logs", logs);
        result.put("total", paged.getTotal());
        result.put("hasMore", paged.getCurrent() < paged.getPages());
        return result;
    }

    // ---- API Key Management ----

    public Map<String, Object> getApiKey(String conversationId, String userId) {
        Conversation conv = conversationService.getById(conversationId, userId);
        if (!Boolean.TRUE.equals(conv.getIsPublished())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Conversation is not published");
        }
        return Collections.singletonMap("apiKey", conv.getApiKey() != null ? conv.getApiKey() : "");
    }

    @Transactional
    public Map<String, Object> regenerateApiKey(String conversationId, String userId) {
        Conversation conv = conversationService.getById(conversationId, userId);
        if (!Boolean.TRUE.equals(conv.getIsPublished())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Conversation is not published");
        }

        String apiKey = "c_" + UUID.randomUUID().toString().replace("-", "");
        String apiKeyHash = sha256Hex(apiKey);

        conv.setApiKey(apiKey);
        conv.setApiKeyHash(apiKeyHash);
        conv.setUpdatedAt(LocalDateTime.now());
        conversationMapper.updateById(conv);

        return Collections.singletonMap("apiKey", apiKey);
    }

    @Transactional
    public Map<String, Object> updateApiDescription(String conversationId, String userId, String apiDescription) {
        Conversation conv = conversationService.getById(conversationId, userId);
        conv.setApiDescription(apiDescription);
        conv.setUpdatedAt(LocalDateTime.now());
        conversationMapper.updateById(conv);
        return toConversationDto(conv);
    }

    // ---- Helpers ----

    static String sha256Hex(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) hexString.append('0');
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }

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
