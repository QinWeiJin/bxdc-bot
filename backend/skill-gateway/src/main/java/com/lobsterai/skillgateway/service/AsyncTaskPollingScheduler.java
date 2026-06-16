package com.lobsterai.skillgateway.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lobsterai.skillgateway.entity.AsyncPollingAuditLog;
import com.lobsterai.skillgateway.entity.AsyncTask;
import com.lobsterai.skillgateway.util.JsonPathUtils;
import com.lobsterai.skillgateway.util.StringUtils;
import javax.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.SocketTimeoutException;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

@Component
public class AsyncTaskPollingScheduler {

    private static final Logger log = LoggerFactory.getLogger(AsyncTaskPollingScheduler.class);
    private static final int MAX_CONSECUTIVE_FAILURES = 3;
    private static final int RESPONSE_TRUNCATE_LENGTH = 4000;

    /** SINGLE_CALL 默认 read timeout（秒），与 maxWaitSeconds 都没设置时用此值。 */
    private static final int DEFAULT_SINGLE_CALL_READ_TIMEOUT_SECONDS = 600;

    /** PERIODIC 任务的固定线程池。20 个并发足够应对 30s 间隔的轮询。 */
    private final ExecutorService periodicExecutor = Executors.newFixedThreadPool(20);

    /** SINGLE_CALL 任务的缓存线程池。每个长调用占一个线程，线程数随任务数动态伸缩。 */
    private final ExecutorService singleCallExecutor = Executors.newCachedThreadPool();

    private final AsyncTaskPollingService pollingService;
    private final ApiProxyService apiProxyService;
    private final AsyncPollingAuditService auditService;
    private final AsyncTaskChatReplyService chatReplyService;
    private final ObjectMapper objectMapper;

    public AsyncTaskPollingScheduler(
            AsyncTaskPollingService pollingService,
            ApiProxyService apiProxyService,
            AsyncPollingAuditService auditService,
            AsyncTaskChatReplyService chatReplyService,
            ObjectMapper objectMapper
    ) {
        this.pollingService = pollingService;
        this.apiProxyService = apiProxyService;
        this.auditService = auditService;
        this.chatReplyService = chatReplyService;
        this.objectMapper = objectMapper;
    }

    @Scheduled(fixedDelayString = "${skill.async.polling.scheduler-interval-ms:30000}")
    public void pollTasks() {
        try {
            List<AsyncTask> tasks = pollingService.findPendingOrPollingTasks(50);
            if (tasks.isEmpty()) return;

            log.info("Polling scheduler picked up {} tasks: {}", tasks.size(),
                    tasks.stream().map(t -> String.format("id=%d/strategy=%s/status=%s",
                            t.getId(), t.getPollStrategy(), t.getStatus())).collect(Collectors.toList()));

            for (AsyncTask task : tasks) {
                // 按 pollStrategy 分发到不同线程池
                if ("SINGLE_CALL".equals(task.getPollStrategy())) {
                    singleCallExecutor.submit(() -> pollSingleTask(task));
                } else {
                    periodicExecutor.submit(() -> pollSingleTask(task));
                }
            }
        } catch (Exception e) {
            log.error("Polling scheduler scan failed", e);
        }
    }

    private void pollSingleTask(AsyncTask task) {
        boolean singleCallMode = "SINGLE_CALL".equals(task.getPollStrategy());

        AsyncPollingAuditLog startLog = auditService.buildBaseLog(task, "GATEWAY_POLL_START");
        Map<String, Object> extra = new HashMap<>();
        extra.put("retryCount", task.getPollRetryCount() != null ? task.getPollRetryCount() : 0);
        extra.put("pollStrategy", task.getPollStrategy() != null ? task.getPollStrategy() : "PERIODIC");
        startLog.setExtraJson(auditService.safeJson(extra));
        auditService.log(startLog);

        try {
            if (task.getStartedAt() == null) {
                pollingService.updateStartedAt(task.getId());
                task.setStartedAt(LocalDateTime.now());
            }

            String status = task.getStatus();
            if (!"PENDING".equals(status) && !"POLLING".equals(status) && !"SINGLE_CALLED".equals(status)) {
                return;
            }

            if (singleCallMode) {
                // ★ 修竞态：SINGLE_CALL 跳过 PENDING→POLLING 转换，直接走 PENDING→SINGLE_CALLED
                if ("PENDING".equals(status)) {
                    pollingService.updateStatus(task.getId(), "SINGLE_CALLED", null);
                }
            } else {
                if ("PENDING".equals(status)) {
                    pollingService.updateStatusAndLastPolled(task.getId(), "POLLING");
                }
            }

            // ============== 构造请求 ==============
            Map<String, Object> pollHeaders = null;
            if (task.getPollHeaders() != null && !StringUtils.isBlank(task.getPollHeaders())) {
                try {
                    pollHeaders = objectMapper.readValue(task.getPollHeaders(), Map.class);
                } catch (Exception ex) {
                    log.warn("Failed to parse pollHeaders JSON for async task {}: {}", task.getId(), ex.getMessage());
                }
            }

            int readTimeoutSeconds;
            String requestUrl;
            if (singleCallMode) {
                // SINGLE_CALL 没有 pollEndpoint —— 直接使用 initial_response 路径里的原 URL。
                // 实际场景下，第三方 SDK 把 initial call 的 URL + body 缓存在 initial_response，
                // 这里我们**重用 initialResponse 时调用的同一个 URL**。
                // 因为 v2.2 没有改 ApiProxyService 的签名，我们用 maxWaitSeconds 作为 readTimeout。
                readTimeoutSeconds = task.getSingleCallReadTimeoutSeconds() != null
                        ? task.getSingleCallReadTimeoutSeconds()
                        : (task.getMaxWaitSeconds() != null ? task.getMaxWaitSeconds() : DEFAULT_SINGLE_CALL_READ_TIMEOUT_SECONDS);
                // 仍然需要发个请求去拿"最终结果"。
                // SINGLE_CALL 的设计：发请求 → 第三方长返回 → readTimeout 内拿到结果。
                // 这里复用 task.getPollEndpoint()：要求上游 Skill 创建任务时
                // 把"要发请求的 URL"放在 pollEndpoint（即使没有轮询），这样 SINGLE_CALL
                // 也能用同一个 ApiProxyService.callApi 路径。
                requestUrl = task.getPollEndpoint();
                if (requestUrl == null || requestUrl.trim().isEmpty()) {
                    String err = "SINGLE_CALL task must have pollEndpoint (reused as long-call URL)";
                    pollingService.updatePollResult(task.getId(), "FAILED", null, err);
                    auditService.log(buildCompleteLog(task, "FAILED", err));
                    return;
                }
            } else {
                readTimeoutSeconds = 30; // PERIODIC 模式用默认 30s（ApiProxyService 的常规重载）
                requestUrl = task.getPollEndpoint();
            }

            Object pollResponse;
            long networkStart = System.currentTimeMillis();
            try {
                if (singleCallMode) {
                    // SINGLE_CALL：反序列化 requestBody（JSON 字符串 → Object），带长 readTimeout 调一次
                    Object requestBody = null;
                    if (task.getRequestBody() != null && !task.getRequestBody().trim().isEmpty()) {
                        try {
                            requestBody = objectMapper.readValue(task.getRequestBody(), Object.class);
                        } catch (Exception bodyParseEx) {
                            log.warn("Failed to deserialize requestBody for SINGLE_CALL task {}: {}",
                                    task.getId(), bodyParseEx.getMessage());
                            // 用原 JSON 字符串作为 body 兜底
                            requestBody = task.getRequestBody();
                        }
                    }
                    pollResponse = apiProxyService.callApi(
                            requestUrl,
                            task.getPollMethod() != null ? task.getPollMethod().toUpperCase() : "GET",
                            pollHeaders,
                            requestBody,
                            readTimeoutSeconds
                    );
                } else {
                    // PERIODIC 走原 4 参数重载（行为零变化）
                    pollResponse = apiProxyService.callApi(
                            requestUrl,
                            task.getPollMethod() != null ? task.getPollMethod().toUpperCase() : "GET",
                            pollHeaders,
                            null
                    );
                }
                long durationMs = System.currentTimeMillis() - networkStart;

                String responseStr = pollResponse instanceof String
                        ? (String) pollResponse
                        : objectMapper.writeValueAsString(pollResponse);

                AsyncPollingAuditLog netLog = auditService.buildBaseLog(task, "NETWORK_REQUEST");
                netLog.setHttpUrl(requestUrl);
                netLog.setHttpMethod(task.getPollMethod() != null ? task.getPollMethod().toUpperCase() : "GET");
                netLog.setDurationMs((int) durationMs);
                netLog.setResponseBody(auditService.truncate(responseStr, RESPONSE_TRUNCATE_LENGTH));
                if (responseStr.length() > RESPONSE_TRUNCATE_LENGTH) {
                    netLog.setResponseTruncated(true);
                }
                if (pollHeaders != null && !pollHeaders.isEmpty()) {
                    try {
                        netLog.setRequestHeadersJson(objectMapper.writeValueAsString(pollHeaders));
                    } catch (Exception ex) {
                        log.warn("Failed to serialize pollHeaders audit for async task {}: {}", task.getId(), ex.getMessage());
                    }
                }
                auditService.log(netLog);

                log.debug("Polled async task {} (external={}) endpoint={} response={}",
                        task.getId(), task.getExternalTaskId(),
                        requestUrl,
                        responseStr.substring(0, Math.min(200, responseStr.length())));

                // ============== SINGLE_CALL 模式：拿到响应即 COMPLETED ==============
                if (singleCallMode) {
                    String result = pollingService.extractResult(responseStr, task.getResultJsonPath());
                    pollingService.updatePollResult(task.getId(), "COMPLETED", result, null);
                    log.info("Async task {} (SINGLE_CALL) completed after {}ms", task.getId(), durationMs);

                    AsyncPollingAuditLog completeLog = auditService.buildBaseLog(task, "GATEWAY_POLL_COMPLETE");
                    completeLog.setStatus("COMPLETED");
                    completeLog.setDurationMs((int) durationMs);
                    auditService.log(completeLog);

                    triggerChatReplyIfTerminal(task);
                    return;
                }
            } catch (Exception netEx) {
                long durationMs = System.currentTimeMillis() - networkStart;

                AsyncPollingAuditLog netErrLog = auditService.buildBaseLog(task, "NETWORK_ERROR");
                netErrLog.setHttpUrl(requestUrl);
                netErrLog.setHttpMethod(task.getPollMethod() != null ? task.getPollMethod().toUpperCase() : "GET");
                netErrLog.setDurationMs((int) durationMs);
                netErrLog.setErrorMessage(netEx.getMessage());
                StringWriter sw = new StringWriter();
                netEx.printStackTrace(new PrintWriter(sw));
                netErrLog.setErrorStack(sw.toString());
                auditService.log(netErrLog);

                if (singleCallMode) {
                    // RestTemplate 把 SocketTimeoutException 包成 ResourceAccessException，
                    // 必须沿 cause 链找真正的 SocketTimeoutException
                    if (isSocketTimeout(netEx)) {
                        // SINGLE_CALL 模式 read timeout 到期 → 标 TIMEOUT
                        String err = "SINGLE_CALL read timeout after " + readTimeoutSeconds + "s";
                        pollingService.updatePollResult(task.getId(), "TIMEOUT", null, err);
                        log.info("Async task {} (SINGLE_CALL) timed out after {}ms", task.getId(), durationMs);
                        AsyncPollingAuditLog timeoutLog = auditService.buildBaseLog(task, "GATEWAY_POLL_COMPLETE");
                        timeoutLog.setStatus("TIMEOUT");
                        timeoutLog.setErrorMessage(err);
                        timeoutLog.setDurationMs((int) durationMs);
                        auditService.log(timeoutLog);

                        triggerChatReplyIfTerminal(task);
                    } else {
                        // SINGLE_CALL 模式其他 HTTP 异常 → 标 FAILED
                        String err = "SINGLE_CALL network error: " + netEx.getMessage();
                        pollingService.updatePollResult(task.getId(), "FAILED", null, err);
                        log.warn("Async task {} (SINGLE_CALL) failed: {}", task.getId(), netEx.getMessage());
                        auditService.log(buildCompleteLog(task, "FAILED", err));

                        triggerChatReplyIfTerminal(task);
                    }
                    return;
                }
                // PERIODIC：转抛给外层 catch 走 retry 逻辑
                throw netEx;
            }

            // ============== PERIODIC 模式：原有的 completion / failed / expired 评估 ==============
            String pollResponseStr = pollResponse instanceof String
                    ? (String) pollResponse
                    : objectMapper.writeValueAsString(pollResponse);

            boolean completed = false;
            boolean isFailed = false;
            boolean expired = false;
            String completionActualValue = null;
            String completionExpectedValue = task.getCompletionValue();

            if (task.getCompletionJsonPath() != null && !StringUtils.isBlank(task.getCompletionJsonPath())) {
                try {
                    Object parsed = objectMapper.readValue(pollResponseStr, Object.class);
                    Object actualObj = JsonPathUtils.extractValueByPath(parsed, task.getCompletionJsonPath());
                    completionActualValue = actualObj != null ? actualObj.toString() : null;
                } catch (Exception ignored) {
                }
            }

            completed = pollingService.evaluateCompletion(pollResponseStr, task.getCompletionJsonPath(), task.getCompletionValue());

            if (!completed && task.getFailedValues() != null && !StringUtils.isBlank(task.getFailedValues())) {
                isFailed = pollingService.evaluateFailure(pollResponseStr, task.getCompletionJsonPath(), task.getFailedValues());
            }

            // ========== Auto-detect 兜底（open spec: async-task-polling-completion）==========
            // 用户配置的 completionJsonPath / failedValues 未命中时，从 pollEndpoint 真实响应
            // 中按候选 status 字段路径推断终态，避免通知中心永远卡 99%。
            // 用户配置优先（已完成 evaluateCompletion）；此处仅作 fallback。
            boolean autoDetected = false;
            if (!completed && !isFailed) {
                AsyncTaskPollingService.TerminalStatus auto = pollingService.autoDetectTerminalStatus(pollResponseStr);
                if (auto == AsyncTaskPollingService.TerminalStatus.SUCCESS) {
                    completed = true;
                    autoDetected = true;
                    log.info("Async task {} auto-detected SUCCESS (user config did not match)", task.getId());
                } else if (auto == AsyncTaskPollingService.TerminalStatus.FAILURE) {
                    isFailed = true;
                    autoDetected = true;
                    log.info("Async task {} auto-detected FAILURE (user config did not match)", task.getId());
                }
            }

            if (!completed && !isFailed && task.getStartedAt() != null) {
                expired = pollingService.isExpired(task.getStartedAt(), task.getMaxWaitSeconds());
            }

            AsyncPollingAuditLog evalLog = auditService.buildBaseLog(task, "EVALUATION");
            evalLog.setCompletionEvaluated(task.getCompletionJsonPath() != null && !StringUtils.isBlank(task.getCompletionJsonPath()));
            if (evalLog.getCompletionEvaluated()) {
                evalLog.setCompletionExpectedValue(completionExpectedValue);
                evalLog.setCompletionActualValue(completionActualValue);
                evalLog.setCompletionMatched(completed);
            }
            evalLog.setFailedEvaluated(task.getFailedValues() != null && !StringUtils.isBlank(task.getFailedValues()));
            if (evalLog.getFailedEvaluated()) {
                evalLog.setFailedMatched(isFailed);
            }
            evalLog.setExpiredEvaluated(task.getMaxWaitSeconds() != null);
            if (evalLog.getExpiredEvaluated()) {
                evalLog.setExpired(expired);
            }
            auditService.log(evalLog);

            if (completed) {
                String result = pollingService.extractResult(pollResponseStr, task.getResultJsonPath());
                pollingService.updatePollResult(task.getId(), "COMPLETED", result, null);
                log.info("Async task {} completed", task.getId());

                AsyncPollingAuditLog completeLog = auditService.buildBaseLog(task, "GATEWAY_POLL_COMPLETE");
                completeLog.setStatus("COMPLETED");
                auditService.log(completeLog);

                triggerChatReplyIfTerminal(task);
                return;
            }

            if (isFailed) {
                String errMsg = "Task failed: status matched failed values";
                pollingService.updatePollResult(task.getId(), "FAILED", null, errMsg);
                log.info("Async task {} failed", task.getId());

                AsyncPollingAuditLog failLog = auditService.buildBaseLog(task, "GATEWAY_POLL_COMPLETE");
                failLog.setStatus("FAILED");
                failLog.setErrorMessage(errMsg);
                auditService.log(failLog);

                triggerChatReplyIfTerminal(task);
                return;
            }

            if (expired) {
                String errMsg = "Task timed out after " + task.getMaxWaitSeconds() + " seconds";
                pollingService.updatePollResult(task.getId(), "TIMEOUT", null, errMsg);
                log.info("Async task {} timed out", task.getId());

                AsyncPollingAuditLog timeoutLog = auditService.buildBaseLog(task, "GATEWAY_POLL_COMPLETE");
                timeoutLog.setStatus("TIMEOUT");
                timeoutLog.setErrorMessage(errMsg);
                auditService.log(timeoutLog);

                triggerChatReplyIfTerminal(task);
                return;
            }

            pollingService.updateLastPolled(task.getId());
        } catch (Exception e) {
            int retryCount = pollingService.incrementRetryCount(task.getId());
            if (retryCount >= MAX_CONSECUTIVE_FAILURES) {
                String errMsg = "Poll failed after " + retryCount + " retries: " + e.getMessage();
                log.warn("Async task {} failed {} consecutive times, marking FAILED", task.getId(), retryCount);
                pollingService.updatePollResult(task.getId(), "FAILED", null, errMsg);

                AsyncPollingAuditLog failLog = auditService.buildBaseLog(task, "GATEWAY_POLL_COMPLETE");
                failLog.setStatus("FAILED");
                failLog.setErrorMessage(errMsg);
                auditService.log(failLog);

                triggerChatReplyIfTerminal(task);
                return;
            }
            log.error("Polling task {} failed (retry {}/{}): {}", task.getId(), retryCount, MAX_CONSECUTIVE_FAILURES, e.getMessage());
            pollingService.updateStatus(task.getId(), "POLLING", "Poll error: " + e.getMessage());
        }
    }

    /**
     * 触发"异步任务回灌对话 + LLM 续答"（open spec: async-task-result-echo-to-chat）。
     *
     * - fire-and-forget：调 chatReplyService.onTaskTerminal（@Async），不阻塞轮询线程
     * - 重新从 DB 读最新 task 状态（status / pollResult / completedAt / errorMessage 都是 updatePollResult 写完的）
     * - chatReplyService 内部 catch 异常，外层不感知
     */
    private void triggerChatReplyIfTerminal(AsyncTask task) {
        try {
            AsyncTask fresh = pollingService.findById(task.getId());
            if (fresh == null) {
                log.warn("[ChatReply] Task {} not found in DB, skip", task.getId());
                return;
            }
            // bxdcbot-multi-turn-async change（漏洞 2 修复）：按 parent_tool_id 分流
            // - 普通 async（parent_tool_id IS NULL）→ 走 echo-to-chat 路径（archive 2026-06-12 行为不变）
            // - Bxdcbot 子 async（parent_tool_id != null）→ 不调 chatReplyService，避免对话流被 N 条单独消息淹没
            //   （Bxdcbot 整体结果统一在 run 跑完时通过 /api/internal/bxdcbot-run/complete 回灌）
            if (fresh.getParentToolId() != null && !fresh.getParentToolId().isEmpty()) {
                log.info("[ChatReply] Skip echo-to-chat for Bxdcbot sub-task taskId={}, parentToolId={} (will be handled by BxdcbotRunCompletion at run-end)",
                        fresh.getId(), fresh.getParentToolId());
                return;
            }
            chatReplyService.onTaskTerminal(fresh);
        } catch (Exception e) {
            log.warn("[ChatReply] trigger failed for task {}: {}", task.getId(), e.getMessage());
        }
    }

    private AsyncPollingAuditLog buildCompleteLog(AsyncTask task, String status, String errMsg) {
        AsyncPollingAuditLog log = auditService.buildBaseLog(task, "GATEWAY_POLL_COMPLETE");
        log.setStatus(status);
        if (errMsg != null) log.setErrorMessage(errMsg);
        return log;
    }

    /**
     * 沿 cause 链找 SocketTimeoutException。
     * RestTemplate 把 SocketTimeoutException 包成 ResourceAccessException，
     * 直接 instanceof 判断会漏掉。
     */
    private boolean isSocketTimeout(Throwable t) {
        Throwable cur = t;
        int depth = 0;
        while (cur != null && depth < 10) {
            if (cur instanceof SocketTimeoutException) {
                return true;
            }
            cur = cur.getCause();
            depth++;
        }
        return false;
    }

    @PreDestroy
    public void shutdown() {
        log.info("AsyncTaskPollingScheduler shutting down thread pools");
        shutdownExecutor(periodicExecutor, "periodic");
        shutdownExecutor(singleCallExecutor, "singleCall");
    }

    private void shutdownExecutor(ExecutorService executor, String name) {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(30, TimeUnit.SECONDS)) {
                log.warn("{} executor did not terminate in 30s, forcing shutdown", name);
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            log.warn("{} executor shutdown interrupted, forcing", name);
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
