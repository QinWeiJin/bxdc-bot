package com.lobsterai.skillgateway.controller;

import com.lobsterai.skillgateway.dto.AsyncTaskNotificationDto;
import com.lobsterai.skillgateway.service.AsyncTaskPollingService;
import com.lobsterai.skillgateway.util.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 异步任务通知中心 Controller。
 *
 * 仅返回走 asyncPoll 分支创建的异步任务（poll_endpoint IS NOT NULL）。
 * 通过 X-User-Id 头识别用户，所有查询按 user_id 过滤，避免跨用户泄露。
 */
@RestController
@RequestMapping("/api/async-tasks")
@CrossOrigin(origins = "*")
public class AsyncTaskNotificationController {

    private static final Logger log = LoggerFactory.getLogger(AsyncTaskNotificationController.class);

    private final AsyncTaskPollingService asyncTaskPollingService;

    public AsyncTaskNotificationController(AsyncTaskPollingService asyncTaskPollingService) {
        this.asyncTaskPollingService = asyncTaskPollingService;
    }

    @GetMapping("/my")
    public ResponseEntity<?> listMy(
            @RequestHeader(value = "X-User-Id", required = false) String userId,
            @RequestParam(value = "unreadOnly", defaultValue = "false") boolean unreadOnly,
            @RequestParam(value = "limit", defaultValue = "20") int limit,
            @RequestParam(value = "parentToolId", required = false) String parentToolId) {
        if (StringUtils.isBlank(userId)) {
            return ResponseEntity.badRequest().body(error("missing X-User-Id header"));
        }
        if (limit <= 0 || limit > 200) limit = 20;
        List<AsyncTaskNotificationDto> items = asyncTaskPollingService.findNotificationsByUser(userId, unreadOnly, limit, parentToolId);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("items", items);
        body.put("count", items.size());
        return ResponseEntity.ok(body);
    }

    @GetMapping("/my/unread-count")
    public ResponseEntity<?> unreadCount(
            @RequestHeader(value = "X-User-Id", required = false) String userId) {
        if (StringUtils.isBlank(userId)) {
            return ResponseEntity.badRequest().body(error("missing X-User-Id header"));
        }
        int count = asyncTaskPollingService.countUnreadByUser(userId);
        Map<String, Object> body = new HashMap<>();
        body.put("count", count);
        return ResponseEntity.ok(body);
    }

    @PostMapping("/{id}/ack")
    public ResponseEntity<?> acknowledge(
            @PathVariable("id") Long taskId,
            @RequestHeader(value = "X-User-Id", required = false) String userId) {
        if (StringUtils.isBlank(userId)) {
            return ResponseEntity.badRequest().body(error("missing X-User-Id header"));
        }
        int affected = asyncTaskPollingService.markRead(taskId, userId);
        Map<String, Object> body = new HashMap<>();
        body.put("ok", affected > 0);
        body.put("affected", affected);
        return ResponseEntity.ok(body);
    }

    /**
     * 单条删除任务。仅允许删除属于自己的任务。
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<?> deleteOne(
            @PathVariable("id") Long taskId,
            @RequestHeader(value = "X-User-Id", required = false) String userId) {
        if (StringUtils.isBlank(userId)) {
            return ResponseEntity.badRequest().body(error("missing X-User-Id header"));
        }
        int affected = asyncTaskPollingService.deleteByIdAndUser(taskId, userId);
        Map<String, Object> body = new HashMap<>();
        body.put("ok", affected > 0);
        body.put("affected", affected);
        return ResponseEntity.ok(body);
    }

    /**
     * 批量删除任务。请求体：{ "ids": [1, 2, 3] }
     */
    @PostMapping("/batch-delete")
    public ResponseEntity<?> batchDelete(
            @RequestBody Map<String, Object> body,
            @RequestHeader(value = "X-User-Id", required = false) String userId) {
        if (StringUtils.isBlank(userId)) {
            return ResponseEntity.badRequest().body(error("missing X-User-Id header"));
        }
        Object idsObj = body.get("ids");
        if (!(idsObj instanceof java.util.List)) {
            return ResponseEntity.badRequest().body(error("ids must be a JSON array"));
        }
        java.util.List<Long> ids = new java.util.ArrayList<>();
        for (Object o : (java.util.List<?>) idsObj) {
            if (o instanceof Number) {
                ids.add(((Number) o).longValue());
            } else if (o instanceof String) {
                try {
                    ids.add(Long.parseLong((String) o));
                } catch (NumberFormatException ignore) {
                    // 跳过非数字项
                }
            }
        }
        int affected = asyncTaskPollingService.deleteByIdsAndUser(userId, ids);
        Map<String, Object> resp = new HashMap<>();
        resp.put("ok", true);
        resp.put("affected", affected);
        resp.put("requested", ids.size());
        return ResponseEntity.ok(resp);
    }

    /**
     * 等待异步任务终态。agent-core BxdcbotRunScheduler 用此端点拿子任务真结果。
     *
     * 参数：
     * - timeout：最多 await 秒数，默认 2
     *
     * 返回：JSON { status, result, errorMessage, pollResult, ... }
     * - 若已终态（COMPLETED / FAILED / TIMEOUT / SINGLE_CALLED 已调完），立即返回
     * - 若还在跑（PENDING / POLLING / SINGLE_CALLED 未完成），在 timeout 秒内每秒查一次 DB
     */
    @GetMapping("/{id}/wait")
    public ResponseEntity<?> waitForTask(
            @PathVariable("id") Long id,
            @RequestParam(value = "timeout", defaultValue = "2") int timeoutSec,
            @RequestHeader(value = "X-User-Id", required = false) String userId) {
        com.lobsterai.skillgateway.entity.AsyncTask task = asyncTaskPollingService.findById(id);
        if (task == null) {
            Map<String, Object> resp = new HashMap<>();
            resp.put("status", "NOT_FOUND");
            resp.put("message", "Async task #" + id + " not found");
            return ResponseEntity.status(404).body(resp);
        }

        // 终态直接返回
        String status = task.getStatus();
        if ("COMPLETED".equals(status) || "FAILED".equals(status) || "TIMEOUT".equals(status)) {
            return ResponseEntity.ok(buildTaskResult(task));
        }
        // SINGLE_CALLED 状态且 poll_result 非空 = 单次长调用已完成
        if ("SINGLE_CALLED".equals(status) && task.getPollResult() != null && !task.getPollResult().isEmpty()) {
            return ResponseEntity.ok(buildTaskResult(task));
        }

        // 非终态：在 timeout 秒内轮询 DB
        int waited = 0;
        while (waited < timeoutSec) {
            try { Thread.sleep(1000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
            waited++;

            task = asyncTaskPollingService.findById(id);
            if (task == null) {
                Map<String, Object> resp = new HashMap<>();
                resp.put("status", "NOT_FOUND");
                return ResponseEntity.status(404).body(resp);
            }
            status = task.getStatus();
            if ("COMPLETED".equals(status) || "FAILED".equals(status) || "TIMEOUT".equals(status)) {
                return ResponseEntity.ok(buildTaskResult(task));
            }
            if ("SINGLE_CALLED".equals(status) && task.getPollResult() != null && !task.getPollResult().isEmpty()) {
                return ResponseEntity.ok(buildTaskResult(task));
            }
        }

        // 超时返回当前状态
        return ResponseEntity.ok(buildTaskResult(task));
    }

    private Map<String, Object> buildTaskResult(com.lobsterai.skillgateway.entity.AsyncTask task) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", task.getId());
        result.put("status", task.getStatus());
        result.put("skillId", task.getSkillId());
        result.put("pollStrategy", task.getPollStrategy());
        if (task.getPollResult() != null) {
            result.put("result", task.getPollResult());
        }
        if (task.getInitialResponse() != null) {
            result.put("initialResponse", task.getInitialResponse());
        }
        if (task.getErrorMessage() != null) {
            result.put("errorMessage", task.getErrorMessage());
        }
        if (task.getMaxWaitSeconds() != null) {
            result.put("maxWaitSeconds", task.getMaxWaitSeconds());
        }
        result.put("pollRetryCount", task.getPollRetryCount());
        if (task.getStartedAt() != null) result.put("startedAt", task.getStartedAt().toString());
        if (task.getCompletedAt() != null) result.put("completedAt", task.getCompletedAt().toString());
        if (task.getCreatedAt() != null) result.put("createdAt", task.getCreatedAt().toString());
        return result;
    }

    private static Map<String, Object> error(String msg) {
        return Collections.singletonMap("error", msg);
    }
}
