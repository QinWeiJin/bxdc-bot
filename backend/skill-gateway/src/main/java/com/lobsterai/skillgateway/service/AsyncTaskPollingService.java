package com.lobsterai.skillgateway.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lobsterai.skillgateway.entity.AsyncTask;
import com.lobsterai.skillgateway.mapper.AsyncTaskMapper;
import com.lobsterai.skillgateway.util.JsonPathUtils;
import com.lobsterai.skillgateway.util.StringUtils;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

@Service
public class AsyncTaskPollingService {

    private final AsyncTaskMapper asyncTaskMapper;
    private final ObjectMapper objectMapper;

    public AsyncTaskPollingService(AsyncTaskMapper asyncTaskMapper, ObjectMapper objectMapper) {
        this.asyncTaskMapper = asyncTaskMapper;
        this.objectMapper = objectMapper;
    }

    public AsyncTask createTask(AsyncTask task) {
        task.setStatus("PENDING");
        task.setCreatedAt(LocalDateTime.now());
        task.setUpdatedAt(LocalDateTime.now());
        asyncTaskMapper.insert(task);
        return task;
    }

    public AsyncTask findById(Long id) {
        return asyncTaskMapper.selectById(id);
    }

    public void updateStatus(Long id, String status, String errorMessage) {
        AsyncTask task = new AsyncTask();
        task.setId(id);
        task.setStatus(status);
        task.setErrorMessage(errorMessage);
        task.setUpdatedAt(LocalDateTime.now());
        asyncTaskMapper.updateById(task);
    }

    public void updatePollResult(Long id, String status, String pollResult, String errorMessage) {
        AsyncTask task = new AsyncTask();
        task.setId(id);
        task.setStatus(status);
        task.setPollResult(pollResult);
        task.setErrorMessage(errorMessage);
        task.setCompletedAt(LocalDateTime.now());
        task.setUpdatedAt(LocalDateTime.now());
        asyncTaskMapper.updateById(task);
    }

    public void updateStartedAt(Long id) {
        AsyncTask task = new AsyncTask();
        task.setId(id);
        task.setStartedAt(LocalDateTime.now());
        task.setUpdatedAt(LocalDateTime.now());
        asyncTaskMapper.updateById(task);
    }

    public void updateStatusAndLastPolled(Long id, String status) {
        AsyncTask task = new AsyncTask();
        task.setId(id);
        task.setStatus(status);
        task.setLastPolledAt(LocalDateTime.now());
        task.setPollRetryCount(0);
        task.setUpdatedAt(LocalDateTime.now());
        asyncTaskMapper.updateById(task);
    }

    public void updateLastPolled(Long id) {
        AsyncTask task = new AsyncTask();
        task.setId(id);
        task.setLastPolledAt(LocalDateTime.now());
        task.setPollRetryCount(0);
        task.setUpdatedAt(LocalDateTime.now());
        asyncTaskMapper.updateById(task);
    }

    public int incrementRetryCount(Long id) {
        AsyncTask current = asyncTaskMapper.selectById(id);
        if (current == null) return 0;
        int newCount = (current.getPollRetryCount() != null ? current.getPollRetryCount() : 0) + 1;
        AsyncTask task = new AsyncTask();
        task.setId(id);
        task.setPollRetryCount(newCount);
        task.setLastPolledAt(LocalDateTime.now());
        task.setUpdatedAt(LocalDateTime.now());
        asyncTaskMapper.updateById(task);
        return newCount;
    }

    public List<AsyncTask> findPendingOrPollingTasks(int limit) {
        return asyncTaskMapper.findPendingOrPolling(limit);
    }

    public String extractTaskId(String initialResponse, String idJsonPath) {
        if (initialResponse == null || StringUtils.isBlank(initialResponse)) return null;
        if (idJsonPath == null || StringUtils.isBlank(idJsonPath)) return null;
        try {
            Object parsed = objectMapper.readValue(initialResponse, Object.class);
            return extractByPath(parsed, idJsonPath);
        } catch (Exception e) {
            return null;
        }
    }

    public boolean evaluateCompletion(String pollResponse, String completionJsonPath, String completionValue) {
        if (pollResponse == null || completionJsonPath == null || completionValue == null) return false;
        try {
            Object parsed = objectMapper.readValue(pollResponse, Object.class);
            String fieldValue = extractByPath(parsed, completionJsonPath);
            return fieldValue != null && fieldValue.equalsIgnoreCase(completionValue);
        } catch (Exception e) {
            return false;
        }
    }

    public boolean evaluateFailure(String pollResponse, String completionJsonPath, String failedValuesJson) {
        if (pollResponse == null || completionJsonPath == null || failedValuesJson == null) return false;
        try {
            Object parsed = objectMapper.readValue(pollResponse, Object.class);
            String fieldValue = extractByPath(parsed, completionJsonPath);
            if (fieldValue == null) return false;
            List<String> failedValues = objectMapper.readValue(failedValuesJson, new TypeReference<List<String>>() {});
            return failedValues.stream().anyMatch(f -> f.equalsIgnoreCase(fieldValue));
        } catch (Exception e) {
            return false;
        }
    }

    public String extractResult(String pollResponse, String resultJsonPath) {
        if (pollResponse == null || resultJsonPath == null || StringUtils.isBlank(resultJsonPath)) return pollResponse;
        try {
            Object parsed = objectMapper.readValue(pollResponse, Object.class);
            Object result = extractValueByPath(parsed, resultJsonPath);
            if (result == null) return pollResponse;
            return result instanceof String ? (String) result : objectMapper.writeValueAsString(result);
        } catch (Exception e) {
            return pollResponse;
        }
    }

    public boolean isExpired(LocalDateTime startedAt, Integer maxWaitSeconds) {
        if (startedAt == null || maxWaitSeconds == null) return false;
        long elapsed = ChronoUnit.SECONDS.between(startedAt, LocalDateTime.now());
        return elapsed >= maxWaitSeconds;
    }

    private String extractByPath(Object obj, String path) {
        Object value = JsonPathUtils.extractValueByPath(obj, path);
        if (value == null) return null;
        if (value instanceof String) return (String) value;
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            return value.toString();
        }
    }

    @SuppressWarnings("unchecked")
    public static Object extractValueByPath(Object obj, String path) {
        return JsonPathUtils.extractValueByPath(obj, path);
    }
}
