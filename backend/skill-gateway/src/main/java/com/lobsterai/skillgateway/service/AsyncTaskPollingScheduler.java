package com.lobsterai.skillgateway.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lobsterai.skillgateway.entity.AsyncTask;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Component
public class AsyncTaskPollingScheduler {

    private static final Logger log = LoggerFactory.getLogger(AsyncTaskPollingScheduler.class);
    private static final int MAX_CONSECUTIVE_FAILURES = 3;

    private final AsyncTaskPollingService pollingService;
    private final ApiProxyService apiProxyService;
    private final ObjectMapper objectMapper;
    private final ExecutorService executor = Executors.newFixedThreadPool(20);

    public AsyncTaskPollingScheduler(
            AsyncTaskPollingService pollingService,
            ApiProxyService apiProxyService,
            ObjectMapper objectMapper
    ) {
        this.pollingService = pollingService;
        this.apiProxyService = apiProxyService;
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
            if (task.getPollHeaders() != null && !task.getPollHeaders().isBlank()) {
                try {
                    pollHeaders = objectMapper.readValue(task.getPollHeaders(), Map.class);
                } catch (Exception ignored) {
                }
            }

            Object pollResponse = apiProxyService.callApi(
                    task.getPollEndpoint(),
                    task.getPollMethod() != null ? task.getPollMethod().toUpperCase() : "GET",
                    pollHeaders,
                    null
            );

            log.debug("Polled async task {} (external={}) endpoint={} response={}",
                    task.getId(), task.getExternalTaskId(),
                    task.getPollEndpoint(),
                    pollResponse instanceof String
                            ? ((String) pollResponse).substring(0, Math.min(200, ((String) pollResponse).length()))
                            : pollResponse);

            String pollResponseStr = pollResponse instanceof String
                    ? (String) pollResponse
                    : objectMapper.writeValueAsString(pollResponse);

            if (pollingService.evaluateCompletion(pollResponseStr, task.getCompletionJsonPath(), task.getCompletionValue())) {
                String result = pollingService.extractResult(pollResponseStr, task.getResultJsonPath());
                pollingService.updatePollResult(task.getId(), "COMPLETED", result, null);
                log.info("Async task {} completed", task.getId());
                return;
            }

            if (task.getFailedValues() != null && !task.getFailedValues().isBlank()) {
                if (pollingService.evaluateFailure(pollResponseStr, task.getCompletionJsonPath(), task.getFailedValues())) {
                    pollingService.updatePollResult(task.getId(), "FAILED", null,
                            "Task failed: status matched failed values");
                    log.info("Async task {} failed", task.getId());
                    return;
                }
            }

            if (task.getStartedAt() != null) {
                if (pollingService.isExpired(task.getStartedAt(), task.getMaxWaitSeconds())) {
                    pollingService.updatePollResult(task.getId(), "TIMEOUT", null,
                            "Task timed out after " + task.getMaxWaitSeconds() + " seconds");
                    log.info("Async task {} timed out", task.getId());
                    return;
                }
            }

            pollingService.updateLastPolled(task.getId());
        } catch (Exception e) {
            int retryCount = pollingService.incrementRetryCount(task.getId());
            if (retryCount >= MAX_CONSECUTIVE_FAILURES) {
                log.warn("Async task {} failed {} consecutive times, marking FAILED", task.getId(), retryCount);
                pollingService.updatePollResult(task.getId(), "FAILED", null,
                        "Poll failed after " + retryCount + " retries: " + e.getMessage());
                return;
            }
            log.error("Polling task {} failed (retry {}/{}): {}", task.getId(), retryCount, MAX_CONSECUTIVE_FAILURES, e.getMessage());
            pollingService.updateStatus(task.getId(), "POLLING", "Poll error: " + e.getMessage());
        }
    }
}
