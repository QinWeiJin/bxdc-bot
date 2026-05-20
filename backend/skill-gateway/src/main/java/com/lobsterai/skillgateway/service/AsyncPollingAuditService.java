package com.lobsterai.skillgateway.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lobsterai.skillgateway.entity.AsyncPollingAuditLog;
import com.lobsterai.skillgateway.entity.AsyncTask;
import com.lobsterai.skillgateway.mapper.AsyncPollingAuditLogMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class AsyncPollingAuditService {

    private static final Logger log = LoggerFactory.getLogger(AsyncPollingAuditService.class);

    private final AsyncPollingAuditLogMapper mapper;
    private final ObjectMapper objectMapper;

    public AsyncPollingAuditService(AsyncPollingAuditLogMapper mapper, ObjectMapper objectMapper) {
        this.mapper = mapper;
        this.objectMapper = objectMapper;
    }

    public void log(AsyncPollingAuditLog entry) {
        try {
            if (entry.getRecordedAt() == null) {
                entry.setRecordedAt(LocalDateTime.now());
            }
            mapper.insert(entry);
        } catch (Exception e) {
            log.warn("Failed to write polling audit log for task {} phase {}: {}",
                    entry.getAsyncTaskId(), entry.getPhase(), e.getMessage());
        }
    }

    public void batchLog(List<AsyncPollingAuditLog> entries) {
        if (entries == null || entries.isEmpty()) return;
        for (AsyncPollingAuditLog entry : entries) {
            log(entry);
        }
    }

    public AsyncPollingAuditLog buildBaseLog(AsyncTask task, String phase) {
        AsyncPollingAuditLog entry = new AsyncPollingAuditLog();
        entry.setAsyncTaskId(task.getId());
        entry.setSkillId(task.getSkillId());
        entry.setUserId(task.getUserId());
        entry.setSessionId(task.getSessionId());
        entry.setPhase(phase);
        entry.setRecordedAt(LocalDateTime.now());
        return entry;
    }

    public String safeJson(Object obj) {
        if (obj == null) return null;
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            return obj.toString();
        }
    }

    public String truncate(String value, int maxLen) {
        if (value == null) return null;
        return value.length() <= maxLen ? value : value.substring(0, maxLen);
    }
}
