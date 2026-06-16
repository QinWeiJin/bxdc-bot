package com.lobsterai.skillgateway.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lobsterai.skillgateway.entity.Conversation;
import com.lobsterai.skillgateway.entity.ConversationMessage;
import com.lobsterai.skillgateway.mapper.ConversationMapper;
import com.lobsterai.skillgateway.mapper.ConversationMessageMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.*;

@Service
public class ConversationService {

    private static final Logger log = LoggerFactory.getLogger(ConversationService.class);
    private static final Set<String> VALID_ROLES = new java.util.HashSet<String>(java.util.Arrays.asList("user", "assistant", "tool", "system"));
    private static final int DEFAULT_LIMIT = 50;
    private static final int MAX_LIMIT = 100;

    private final ConversationMapper conversationMapper;
    private final ConversationMessageMapper messageMapper;
    private final ObjectMapper objectMapper;

    public ConversationService(ConversationMapper conversationMapper,
                               ConversationMessageMapper messageMapper,
                               ObjectMapper objectMapper) {
        this.conversationMapper = conversationMapper;
        this.messageMapper = messageMapper;
        this.objectMapper = objectMapper;
    }

    // ---- Conversation CRUD ----

    public List<Conversation> listByUserId(String userId) {
        return conversationMapper.selectByUserIdOrderByUpdatedAt(userId);
    }

    public Conversation getById(String conversationId, String userId) {
        Conversation conv = conversationMapper.selectByConversationId(conversationId);
        if (conv == null || !conv.getUserId().equals(userId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Conversation not found");
        }
        return conv;
    }

    @Transactional
    public Conversation create(String userId, String name, List<Long> enabledSkills) {
        return create(userId, name, enabledSkills, Collections.<Long>emptyList());
    }

    @Transactional
    public Conversation create(String userId, String name, List<Long> enabledSkills, List<Long> enabledFiles) {
        Conversation conv = new Conversation();
        conv.setConversationId(UUID.randomUUID().toString());
        conv.setUserId(userId);
        conv.setName(name != null ? name : "");
        conv.setEnabledSkills(skillsToJson(enabledSkills));
        conv.setEnabledFiles(filesToJson(enabledFiles));
        conv.setStatus("active");
        conv.setCreatedAt(LocalDateTime.now());
        conv.setUpdatedAt(LocalDateTime.now());
        conversationMapper.insert(conv);
        return conv;
    }

    @Transactional
    public Conversation update(String conversationId, String userId, String name, List<Long> enabledSkills) {
        return update(conversationId, userId, name, enabledSkills, null);
    }

    @Transactional
    public Conversation update(String conversationId, String userId, String name,
                               List<Long> enabledSkills, List<Long> enabledFiles) {
        Conversation conv = getById(conversationId, userId);
        if (name != null) {
            conv.setName(name);
        }
        if (enabledSkills != null) {
            conv.setEnabledSkills(skillsToJson(enabledSkills));
        }
        if (enabledFiles != null) {
            conv.setEnabledFiles(filesToJson(enabledFiles));
        }
        conv.setUpdatedAt(LocalDateTime.now());
        conversationMapper.updateById(conv);
        return conv;
    }

    /**
     * 将 fileId 追加到指定对话的 enabled_files。
     * 幂等：已存在则不重复添加。
     */
    @Transactional
    public void appendEnabledFile(String conversationId, String userId, Long fileId) {
        Conversation conv = getById(conversationId, userId);
        List<Long> ids = parseFileIds(conv.getEnabledFiles());
        if (!ids.contains(fileId)) {
            ids.add(fileId);
            conv.setEnabledFiles(filesToJson(ids));
            conv.setUpdatedAt(LocalDateTime.now());
            conversationMapper.updateById(conv);
            log.info("appendEnabledFile: conv={}, fileId={}", conversationId, fileId);
        }
    }

    /**
     * 从<b>指定用户</b>的所有对话的 enabled_files 中移除指定 fileId。
     * 用于 file_delete 成功后清理孤行引用。
     */
    @Transactional
    public int removeEnabledFileFromAllConversations(Long fileId, String userId) {
        List<Conversation> all = conversationMapper.selectByUserIdOrderByUpdatedAt(userId);
        int updated = 0;
        for (Conversation conv : all) {
            List<Long> ids = parseFileIds(conv.getEnabledFiles());
            if (ids.remove(fileId)) {
                conv.setEnabledFiles(filesToJson(ids));
                conv.setUpdatedAt(LocalDateTime.now());
                conversationMapper.updateById(conv);
                updated++;
            }
        }
        if (updated > 0) {
            log.info("removeEnabledFileFromAllConversations: fileId={}, cleaned={}", fileId, updated);
        }
        return updated;
    }

    @Transactional
    public void delete(String conversationId, String userId) {
        Conversation conv = getById(conversationId, userId);
        messageMapper.deleteByConversationId(conversationId);
        conversationMapper.deleteById(conv.getId());
    }

    // ---- Messages ----

    public Map<String, Object> getMessages(String conversationId, String userId,
                                            String cursorStr, Integer rawLimit) {
        // verify conversation exists and belongs to user
        getById(conversationId, userId);

        int limit = Math.min(rawLimit != null ? rawLimit : DEFAULT_LIMIT, MAX_LIMIT);
        LocalDateTime cursor = null;
        if (cursorStr != null && !cursorStr.isEmpty()) {
            try {
                cursor = LocalDateTime.parse(cursorStr);
            } catch (Exception e) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid cursor format, expected ISO 8601");
            }
        }

        // Fetch one extra to determine hasMore
        List<ConversationMessage> messages = messageMapper.selectByConversationIdCursor(
                conversationId, cursor, limit + 1);

        boolean hasMore = messages.size() > limit;
        if (hasMore) {
            messages = messages.subList(0, limit);
        }

        List<Map<String, Object>> result = new ArrayList<>();
        for (ConversationMessage msg : messages) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("message_id", msg.getMessageId());
            m.put("role", msg.getRole());
            m.put("content", msg.getContent());
            m.put("skill_calls", msg.getSkillCalls());
            m.put("skill_outputs", msg.getSkillOutputs());
            m.put("source", msg.getSource());
            // async-task-result-echo-to-chat: 异步任务结果消息专用字段
            m.put("async_task_id", msg.getAsyncTaskId());
            m.put("summary_pending", msg.getSummaryPending());
            m.put("summary_text", msg.getSummaryText());
            m.put("summary_generated_at", msg.getSummaryGeneratedAt() != null ? msg.getSummaryGeneratedAt().toString() : null);
            m.put("created_at", msg.getCreatedAt() != null ? msg.getCreatedAt().toString() : null);
            result.add(m);
        }

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("messages", result);
        response.put("hasMore", hasMore);
        return response;
    }

    @Transactional
    public Map<String, Object> saveMessages(String conversationId, String userId,
                                             List<Map<String, Object>> messages) {
        Conversation conv = getById(conversationId, userId);

        if (messages == null || messages.isEmpty()) {
            java.util.Map<String, Object> emptyResult = new java.util.LinkedHashMap<String, Object>();
            emptyResult.put("ok", true);
            emptyResult.put("count", 0);
            return emptyResult;
        }

        int count = 0;
        for (Map<String, Object> raw : messages) {
            String role = String.valueOf(raw.getOrDefault("role", ""));
            if (!VALID_ROLES.contains(role)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Invalid message role: " + role + ". Must be one of: " + String.join(", ", VALID_ROLES));
            }

            ConversationMessage msg = new ConversationMessage();
            msg.setMessageId(UUID.randomUUID().toString());
            msg.setConversationId(conversationId);
            msg.setRole(role);
            msg.setContent(String.valueOf(raw.getOrDefault("content", "")));
            msg.setSkillCalls(raw.containsKey("skill_calls") ? toJson(raw.get("skill_calls")) : null);
            msg.setSkillOutputs(raw.containsKey("skill_outputs") ? toJson(raw.get("skill_outputs")) : null);
            msg.setCreatedAt(LocalDateTime.now());
            messageMapper.insert(msg);
            count++;
        }

        // Refresh conversation updated_at
        conv.setUpdatedAt(LocalDateTime.now());
        conversationMapper.updateById(conv);

        java.util.Map<String, Object> result = new java.util.LinkedHashMap<String, Object>();
        result.put("ok", true);
        result.put("count", count);
        return result;
    }

    // ---- Helper methods ----

    private String skillsToJson(List<Long> skillIds) {
        if (skillIds == null || skillIds.isEmpty()) return "[]";
        try {
            return objectMapper.writeValueAsString(skillIds);
        } catch (JsonProcessingException e) {
            log.warn("Failed to serialize enabled_skills: {}", e.getMessage());
            return "[]";
        }
    }

    private String filesToJson(List<Long> fileIds) {
        if (fileIds == null) return "[]";
        if (fileIds.isEmpty()) return "[]";
        try {
            return objectMapper.writeValueAsString(fileIds);
        } catch (JsonProcessingException e) {
            log.warn("Failed to serialize enabled_files: {}", e.getMessage());
            return "[]";
        }
    }

    /**
     * 反序列化 enabled_files JSON 字符串。
     * - null 或空字符串 → 返回空列表
     * - JSON 解析失败 → 返回空列表（容错）
     */
    private List<Long> parseFileIds(String json) {
        List<Long> result = new ArrayList<Long>();
        if (json == null || json.isEmpty() || "null".equals(json)) return result;
        try {
            Long[] arr = objectMapper.readValue(json, Long[].class);
            if (arr != null) {
                for (Long id : arr) {
                    if (id != null) result.add(id);
                }
            }
        } catch (Exception e) {
            log.warn("Failed to parse enabled_files: {}", e.getMessage());
        }
        return result;
    }

    private String toJson(Object obj) {
        if (obj == null) return null;
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            log.warn("Failed to serialize object to JSON: {}", e.getMessage());
            return null;
        }
    }
}
