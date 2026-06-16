package com.lobsterai.skillgateway.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lobsterai.skillgateway.dto.AsyncTaskNotificationDto;
import com.lobsterai.skillgateway.entity.AsyncTask;
import com.lobsterai.skillgateway.entity.Skill;
import com.lobsterai.skillgateway.mapper.AsyncTaskMapper;
import com.lobsterai.skillgateway.util.JsonPathUtils;
import com.lobsterai.skillgateway.util.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class AsyncTaskPollingService {

    private static final Logger log = LoggerFactory.getLogger(AsyncTaskPollingService.class);

    /**
     * Auto-detect 候选 status 字段路径（按优先级）。
     * 用户配置的 completionJsonPath 优先；若未配置 / 路径不命中 / 值不匹配，
     * 按此列表顺序提取 status-like 字段，匹配终态白名单。
     * open spec: async-task-polling-completion
     */
    private static final List<String> STATUS_FIELD_CANDIDATES = Collections.unmodifiableList(Arrays.asList(
            "$.status",
            "$.state",
            "$.code",
            "$.data.status",
            "$.data.state",
            "$.data.code",
            "$.result.status",
            "$.result.code"
    ));

    /**
     * Auto-detect 成功终态值（大小写不敏感）。
     * 包含 `2` 是兼容部分金融类 API 用 `2` 表示成功。
     */
    private static final Set<String> TERMINAL_SUCCESS_VALUES;
    static {
        Set<String> s = new HashSet<>();
        s.add("SUCCESS");
        s.add("COMPLETED");
        s.add("FINISHED");
        s.add("DONE");
        s.add("SUCCEED");
        s.add("OK");
        s.add("2");
        TERMINAL_SUCCESS_VALUES = Collections.unmodifiableSet(s);
    }

    /**
     * Auto-detect 失败终态值（大小写不敏感）。
     */
    private static final Set<String> TERMINAL_FAILURE_VALUES;
    static {
        Set<String> s = new HashSet<>();
        s.add("FAILED");
        s.add("FAILURE");
        s.add("ERROR");
        s.add("CANCELLED");
        s.add("CANCELED");
        s.add("TIMEOUT");
        s.add("EXPIRED");
        TERMINAL_FAILURE_VALUES = Collections.unmodifiableSet(s);
    }

    /** Auto-detect 命中的终态分类。 */
    public enum TerminalStatus {
        SUCCESS, FAILURE, UNKNOWN
    }

    private final AsyncTaskMapper asyncTaskMapper;
    private final ObjectMapper objectMapper;
    private final SkillService skillService;

    public AsyncTaskPollingService(AsyncTaskMapper asyncTaskMapper, ObjectMapper objectMapper,
                                   SkillService skillService) {
        this.asyncTaskMapper = asyncTaskMapper;
        this.objectMapper = objectMapper;
        this.skillService = skillService;
    }

    public AsyncTask createTask(AsyncTask task) {
        task.setStatus("PENDING");
        task.setCreatedAt(LocalDateTime.now());
        task.setUpdatedAt(LocalDateTime.now());
        asyncTaskMapper.insert(task);
        return task;
    }

    /**
     * 创建 Bxdcbot 子任务的合成通知（用于 sync 子任务也要显示在通知中心的场景）。
     * subtask_only=1，poll_endpoint 必为 NULL，status 必为 COMPLETED。
     * 幂等：同一 (parentToolId, skillName) 组合在已存在 COMPLETED 行时直接返回，不重复插入。
     */
    public AsyncTask createBxdcbotSubtaskNotification(
            String userId, String sessionId, String skillName, String parentToolId, Long parentSkillId,
            Long skillId, String result, Long elapsedSeconds) {
        // 幂等检查：同 parentToolId + skillName + status=COMPLETED 是否已有
        try {
            List<AsyncTask> existing = asyncTaskMapper.selectList(
                    new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<AsyncTask>()
                            .eq(AsyncTask::getUserId, userId)
                            .eq(AsyncTask::getParentToolId, parentToolId)
                            .eq(AsyncTask::getSkillId, skillId)
                            .eq(AsyncTask::getStatus, "COMPLETED")
                            .eq(AsyncTask::getSubtaskOnly, 1)
                            .last("LIMIT 1"));
            if (existing != null && !existing.isEmpty()) {
                return existing.get(0);
            }
        } catch (Exception ignore) { /* 幂等检查失败不影响插入 */ }

        AsyncTask task = new AsyncTask();
        task.setUserId(userId);
        task.setSessionId(sessionId);
        task.setSkillId(skillId);
        task.setStatus("COMPLETED");
        task.setParentToolId(parentToolId);
        task.setParentSkillId(parentSkillId);
        task.setSubtaskOnly(1);
        task.setPollResult(result != null ? result : "{}");
        task.setStartedAt(LocalDateTime.now());
        task.setCompletedAt(LocalDateTime.now());
        task.setCreatedAt(LocalDateTime.now());
        task.setUpdatedAt(LocalDateTime.now());
        // 默认未读：notified_at = NULL
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

    /**
     * 按 session 维度查找最近的同签名任务。
     * null 输入直接返回 null（由调用方处理）；窗口由调用方传（PER_SESSION / NO_SESSION）。
     */
    public AsyncTask findRecentBySignatureInSession(
            String userId, String sessionId, String signature, int windowSeconds) {
        if (userId == null || signature == null) return null;
        return asyncTaskMapper.findRecentBySignatureInSession(userId, sessionId, signature, windowSeconds);
    }

    public List<AsyncTask> findPendingOrPollingTasks(int limit) {
        return asyncTaskMapper.findPendingOrPolling(limit);
    }

    public List<AsyncTask> findActiveBySessionId(String sessionId) {
        return asyncTaskMapper.findBySessionId(sessionId);
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

    /**
     * Auto-detect pollEndpoint 响应中的 status 字段是否命中终态。
     * 按 {@link #STATUS_FIELD_CANDIDATES} 候选路径顺序提取 status-like 字段，
     * 命中 {@link #TERMINAL_SUCCESS_VALUES} 返回 SUCCESS，命中 {@link #TERMINAL_FAILURE_VALUES} 返回 FAILURE。
     *
     * <p>大小写不敏感；用户配置的 completionJsonPath 优先（由 Scheduler 先调 evaluateCompletion，
     * 该方法仅作 fallback）。</p>
     *
     * <p>open spec: async-task-polling-completion</p>
     *
     * @param pollResponse pollEndpoint 原始响应字符串（JSON）
     * @return TerminalStatus.SUCCESS / FAILURE / UNKNOWN
     */
    public TerminalStatus autoDetectTerminalStatus(String pollResponse) {
        if (pollResponse == null || StringUtils.isBlank(pollResponse)) return TerminalStatus.UNKNOWN;
        Object parsed;
        try {
            parsed = objectMapper.readValue(pollResponse, Object.class);
        } catch (Exception e) {
            return TerminalStatus.UNKNOWN;
        }
        for (String path : STATUS_FIELD_CANDIDATES) {
            try {
                Object value = JsonPathUtils.extractValueByPath(parsed, path);
                if (value == null) continue;
                String stringValue = value.toString();
                String upper = stringValue.toUpperCase();
                if (TERMINAL_SUCCESS_VALUES.contains(upper)) {
                    log.debug("Auto-detect hit status field at path={} value={}", path, stringValue);
                    return TerminalStatus.SUCCESS;
                }
                if (TERMINAL_FAILURE_VALUES.contains(upper)) {
                    log.debug("Auto-detect hit failure field at path={} value={}", path, stringValue);
                    return TerminalStatus.FAILURE;
                }
            } catch (Exception ignored) {
                // 单个路径解析失败不影响其他路径尝试
            }
        }
        return TerminalStatus.UNKNOWN;
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

    // ============================ 通知中心 ============================

    /**
     * 列出指定用户的异步任务（仅走 asyncPoll 分支），转 DTO。
     * 同时触发一次"7 天前已完成/失败/超时且未读"的自动清理。
     */
    public List<AsyncTaskNotificationDto> findNotificationsByUser(String userId, boolean unreadOnly, int limit, String parentToolId) {
        // 自动清理历史未读任务，避免用户登录时看到几个月前的旧任务
        try {
            asyncTaskMapper.autoMarkStaleAsRead();
        } catch (Exception ignore) {
            // 自动清理失败不应阻塞列表查询
        }
        List<AsyncTask> tasks = asyncTaskMapper.findByUserAndAsyncPoll(userId, unreadOnly, limit, parentToolId);
        if (tasks.isEmpty()) return java.util.Collections.emptyList();

        // 预加载 skill 名称，避免 N+1
        Map<Long, String> skillNameCache = new HashMap<>();
        Map<Long, String> parentSkillNameCache = new HashMap<>();
        for (AsyncTask t : tasks) {
            if (t.getSkillId() != null) {
                skillNameCache.computeIfAbsent(t.getSkillId(), sid -> {
                    return skillService.getSkillByIdForUser(sid, userId)
                            .map(Skill::getName)
                            .orElse(null);
                });
            }
            if (t.getParentSkillId() != null) {
                parentSkillNameCache.computeIfAbsent(t.getParentSkillId(), psid -> {
                    return skillService.getSkillByIdForUser(psid, userId)
                            .map(Skill::getName)
                            .orElse(null);
                });
            }
        }

        return tasks.stream().map(t -> {
            String skillName = t.getSkillId() == null ? null : skillNameCache.get(t.getSkillId());
            long elapsed = 0;
            if (t.getStartedAt() != null) {
                // 终态（COMPLETED / FAILED / TIMEOUT）用 completedAt 冻结耗时，
                // 否则用 now() 实时增长。未启动的任务 elapsed=0。
                String status = t.getStatus();
                boolean isTerminal = "COMPLETED".equals(status) || "FAILED".equals(status) || "TIMEOUT".equals(status);
                LocalDateTime end = (isTerminal && t.getCompletedAt() != null) ? t.getCompletedAt() : LocalDateTime.now();
                elapsed = ChronoUnit.SECONDS.between(t.getStartedAt(), end);
                if (elapsed < 0) elapsed = 0;
            }
            String preview = buildPreview(t);
            Integer maxWaitSec = t.getMaxWaitSeconds();
            // SINGLE_CALL 用 singleCallReadTimeoutSeconds 作为 maxWaitSeconds（如果没有显式设置 maxWaitSeconds）
            if (maxWaitSec == null && t.getSingleCallReadTimeoutSeconds() != null) {
                maxWaitSec = t.getSingleCallReadTimeoutSeconds();
            }
            AsyncTaskNotificationDto dto = AsyncTaskNotificationDto.from(t, skillName, 0, elapsed, preview, maxWaitSec);
            if (t.getParentSkillId() != null) {
                dto.setParentSkillName(parentSkillNameCache.get(t.getParentSkillId()));
            }
            return dto;
        }).collect(Collectors.toList());
    }

    public int countUnreadByUser(String userId) {
        return asyncTaskMapper.countUnreadByUser(userId);
    }

    public int markRead(Long taskId, String userId) {
        return asyncTaskMapper.markRead(taskId, userId);
    }

    public int autoMarkStaleAsRead() {
        return asyncTaskMapper.autoMarkStaleAsRead();
    }

    public int deleteByIdAndUser(Long taskId, String userId) {
        return asyncTaskMapper.deleteByIdAndUser(taskId, userId);
    }

    public int deleteByIdsAndUser(String userId, java.util.List<Long> ids) {
        if (ids == null || ids.isEmpty()) return 0;
        return asyncTaskMapper.deleteByIdsAndUser(userId, ids);
    }

    private String buildPreview(AsyncTask t) {
        if (!"COMPLETED".equals(t.getStatus())) return null;
        String src = t.getPollResult();
        if (src == null || src.isEmpty()) src = t.getInitialResponse();
        if (src == null || src.isEmpty()) return null;
        // 截断到 200 字符，避免列表过长
        if (src.length() > 200) return src.substring(0, 200) + "…";
        return src;
    }
}
