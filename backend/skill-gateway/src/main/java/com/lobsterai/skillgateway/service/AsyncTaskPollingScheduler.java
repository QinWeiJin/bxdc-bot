package com.lobsterai.skillgateway.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lobsterai.skillgateway.entity.AsyncPollingAuditLog;
import com.lobsterai.skillgateway.entity.AsyncTask;
import com.lobsterai.skillgateway.util.JsonPathUtils;
import com.lobsterai.skillgateway.util.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Component
public class AsyncTaskPollingScheduler {

    private static final Logger log = LoggerFactory.getLogger(AsyncTaskPollingScheduler.class);
    private static final int MAX_CONSECUTIVE_FAILURES = 3;
    private static final int RESPONSE_TRUNCATE_LENGTH = 4000;

    private final AsyncTaskPollingService pollingService;
    private final ApiProxyService apiProxyService;
    private final AsyncPollingAuditService auditService;
    private final ObjectMapper objectMapper;
    private final ExecutorService executor = Executors.newFixedThreadPool(20);
    private final java.util.concurrent.ConcurrentHashMap<Long, java.util.concurrent.CompletableFuture<String>> pendingFutures = new java.util.concurrent.ConcurrentHashMap<>();

    public java.util.concurrent.CompletableFuture<String> registerFuture(Long asyncTaskId) {
        java.util.concurrent.CompletableFuture<String> future = new java.util.concurrent.CompletableFuture<>();
        pendingFutures.put(asyncTaskId, future);
        return future;
    }

    private void completeFuture(Long asyncTaskId, String result) {
        java.util.concurrent.CompletableFuture<String> future = pendingFutures.remove(asyncTaskId);
        if (future != null) {
            future.complete(result);
        }
    }

    public AsyncTaskPollingScheduler(
            AsyncTaskPollingService pollingService,
            ApiProxyService apiProxyService,
            AsyncPollingAuditService auditService,
            ObjectMapper objectMapper
    ) {
        this.pollingService = pollingService;
        this.apiProxyService = apiProxyService;
        this.auditService = auditService;
        this.objectMapper = objectMapper;
    }

    @Scheduled(fixedDelayString = "${skill.async.polling.scheduler-interval-ms:30000}")
    public void pollTasks() {
        try {
            List<AsyncTask> tasks = pollingService.findPendingOrPollingTasks(50);
            if (tasks.isEmpty()) return;

            log.debug("Polling scheduler picked up {} tasks", tasks.size());

            for (AsyncTask task : tasks) {
                executor.submit(() -> pollSingleTask(task));
            }
        } catch (Exception e) {
            log.error("Polling scheduler scan failed", e);
        }
    }

    private void pollSingleTask(AsyncTask task) {
        AsyncPollingAuditLog startLog = auditService.buildBaseLog(task, "GATEWAY_POLL_START");
        startLog.setExtraJson(auditService.safeJson(Collections.singletonMap("retryCount", task.getPollRetryCount() != null ? task.getPollRetryCount() : 0)));
        auditService.log(startLog);

        try {
            if (task.getStartedAt() == null) {
                pollingService.updateStartedAt(task.getId());
                task.setStartedAt(LocalDateTime.now());
            }

            String status = task.getStatus();
            if (!"PENDING".equals(status) && !"POLLING".equals(status)) {
                return;
            }

            if ("PENDING".equals(status)) {
                pollingService.updateStatusAndLastPolled(task.getId(), "POLLING");
            }

            Map<String, Object> pollHeaders = null;
            if (task.getPollHeaders() != null && !StringUtils.isBlank(task.getPollHeaders())) {
                try {
                    pollHeaders = objectMapper.readValue(task.getPollHeaders(), Map.class);
                } catch (Exception ex) {
                    log.warn("Failed to parse pollHeaders JSON for async task {}: {}", task.getId(), ex.getMessage());
                }
            }

            Object pollResponse;
            long networkStart = System.currentTimeMillis();
            try {
                pollResponse = apiProxyService.callApi(
                        task.getPollEndpoint(),
                        task.getPollMethod() != null ? task.getPollMethod().toUpperCase() : "GET",
                        pollHeaders,
                        null
                );
                long durationMs = System.currentTimeMillis() - networkStart;

                String responseStr = pollResponse instanceof String
                        ? (String) pollResponse
                        : objectMapper.writeValueAsString(pollResponse);

                AsyncPollingAuditLog netLog = auditService.buildBaseLog(task, "NETWORK_REQUEST");
                netLog.setHttpUrl(task.getPollEndpoint());
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
                        task.getPollEndpoint(),
                        responseStr.substring(0, Math.min(200, responseStr.length())));
            } catch (Exception netEx) {
                long durationMs = System.currentTimeMillis() - networkStart;

                AsyncPollingAuditLog netErrLog = auditService.buildBaseLog(task, "NETWORK_ERROR");
                netErrLog.setHttpUrl(task.getPollEndpoint());
                netErrLog.setHttpMethod(task.getPollMethod() != null ? task.getPollMethod().toUpperCase() : "GET");
                netErrLog.setDurationMs((int) durationMs);
                netErrLog.setErrorMessage(netEx.getMessage());
                StringWriter sw = new StringWriter();
                netEx.printStackTrace(new PrintWriter(sw));
                netErrLog.setErrorStack(sw.toString());
                auditService.log(netErrLog);

                throw netEx;
            }

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
                completeFuture(task.getId(), result != null ? result : pollResponseStr);
                log.info("Async task {} completed", task.getId());

                AsyncPollingAuditLog completeLog = auditService.buildBaseLog(task, "GATEWAY_POLL_COMPLETE");
                completeLog.setStatus("COMPLETED");
                auditService.log(completeLog);
                return;
            }

            if (isFailed) {
                String errMsg = "Task failed: status matched failed values";
                pollingService.updatePollResult(task.getId(), "FAILED", null, errMsg);
                completeFuture(task.getId(), "{\"status\":\"FAILED\",\"errorMessage\":\"" + errMsg + "\"}");
                log.info("Async task {} failed", task.getId());

                AsyncPollingAuditLog failLog = auditService.buildBaseLog(task, "GATEWAY_POLL_COMPLETE");
                failLog.setStatus("FAILED");
                failLog.setErrorMessage(errMsg);
                auditService.log(failLog);
                return;
            }

            if (expired) {
                String errMsg = "Task timed out after " + task.getMaxWaitSeconds() + " seconds";
                pollingService.updatePollResult(task.getId(), "TIMEOUT", null, errMsg);
                completeFuture(task.getId(), "{\"status\":\"TIMEOUT\",\"errorMessage\":\"" + errMsg + "\"}");
                log.info("Async task {} timed out", task.getId());

                AsyncPollingAuditLog timeoutLog = auditService.buildBaseLog(task, "GATEWAY_POLL_COMPLETE");
                timeoutLog.setStatus("TIMEOUT");
                timeoutLog.setErrorMessage(errMsg);
                auditService.log(timeoutLog);
                return;
            }

            pollingService.updateLastPolled(task.getId());
        } catch (Exception e) {
            int retryCount = pollingService.incrementRetryCount(task.getId());
            if (retryCount >= MAX_CONSECUTIVE_FAILURES) {
                String errMsg = "Poll failed after " + retryCount + " retries: " + e.getMessage();
                log.warn("Async task {} failed {} consecutive times, marking FAILED", task.getId(), retryCount);
                pollingService.updatePollResult(task.getId(), "FAILED", null, errMsg);
                completeFuture(task.getId(), "{\"status\":\"FAILED\",\"errorMessage\":\"" + errMsg + "\"}");

                AsyncPollingAuditLog failLog = auditService.buildBaseLog(task, "GATEWAY_POLL_COMPLETE");
                failLog.setStatus("FAILED");
                failLog.setErrorMessage(errMsg);
                auditService.log(failLog);
                return;
            }
            log.error("Polling task {} failed (retry {}/{}): {}", task.getId(), retryCount, MAX_CONSECUTIVE_FAILURES, e.getMessage());
            pollingService.updateStatus(task.getId(), "POLLING", "Poll error: " + e.getMessage());
        }
    }
}
