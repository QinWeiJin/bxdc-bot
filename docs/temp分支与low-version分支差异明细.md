# temp 分支与 low-version 分支差异明细（Java 项目手动合并指南）

> **用途**：在没有 git 工具时，按照本文档手动修改 low-version 代码即得到 temp 版本。
> **基准**：`low-version`（73286ff）→ **目标**：`temp`（fe97617）
> **范围**：`backend/skill-gateway/` 下的 Java 源码、pom.xml、SQL schema、配置文件
> **涉及文件**：3 个 Java 文件 + 1 个 pom.xml + 5 个 SQL/配置 = 共 9 个文件

---

## 文件清单

| # | 文件 | 变化类型 |
|---|------|---------|
| 1 | `pom.xml` | 修改：+1 行 |
| 2 | `SkillExecutionService.java` | 修改：+71 行 / -22 行 |
| 3 | `AsyncTaskPollingScheduler.java` | 修改：+157 行 / -43 行 |
| 4 | `SystemSkillController.java` | 修改：+31 行 / -28 行 |
| 5 | `schema-mysql.sql` | 修改：3 处 |
| 6 | `schema-h2.sql` | 修改：2 行 |
| 7 | `schema-h2.sql`（test） | 修改：2 行 |
| 8 | `schema.sql` | 修改：删除 13 行 + 缩进 |
| 9 | `application.properties` | 新建：从 .example 重命名并修改内容 |

---

## 1. pom.xml

**文件**：`backend/skill-gateway/pom.xml`

**操作**：在 `<artifactId>h2</artifactId>` 之后，`<scope>test</scope>` 之前添加一行。

**查找**（low-version 原样）：
```xml
        <dependency>
            <groupId>com.h2database</groupId>
            <artifactId>h2</artifactId>
            <scope>test</scope>
        </dependency>
```

**改为**：
```xml
        <dependency>
            <groupId>com.h2database</groupId>
            <artifactId>h2</artifactId>
            <version>1.4.200</version>
            <scope>test</scope>
        </dependency>
```

---

## 2. SkillExecutionService.java

**文件**：`backend/skill-gateway/src/main/java/com/lobsterai/skillgateway/service/SkillExecutionService.java`

### 2.1 修改 import 区域（第 6-10 行）

**查找**（low-version 原样）：
```java
import com.lobsterai.skillgateway.audit.HttpClientAuditMode;
import com.lobsterai.skillgateway.entity.AsyncTask;
import com.lobsterai.skillgateway.entity.ServerLedger;
import com.lobsterai.skillgateway.entity.Skill;
import com.lobsterai.skillgateway.util.StringUtils;
```

**改为**：
```java
import com.lobsterai.skillgateway.audit.HttpClientAuditMode;
import com.lobsterai.skillgateway.config.DedupConfig;
import com.lobsterai.skillgateway.entity.AsyncTask;
import com.lobsterai.skillgateway.entity.ServerLedger;
import com.lobsterai.skillgateway.entity.Skill;
import com.lobsterai.skillgateway.util.RequestSignatureUtil;
import com.lobsterai.skillgateway.util.StringUtils;
```

> 即新增两行 import：
> - `import com.lobsterai.skillgateway.config.DedupConfig;`（插在 `HttpClientAuditMode` 之后）
> - `import com.lobsterai.skillgateway.util.RequestSignatureUtil;`（插在 `Skill` 之后）

### 2.2 替换 `executeApiSkillAsync` 方法（约第 437-520 行）

**查找** low-version 的完整方法体（从 `@SuppressWarnings` 行开始到方法末尾的 `}`）：

```java
    @SuppressWarnings("unchecked")
    private Object executeApiSkillAsync(Skill skill, Map<String, Object> config, Object parameters, String userId, String sessionId) throws Exception {
        // Step 1: Execute initial API request
        Object initialResponse = executeApiSkill(config, parameters);
        String initialResponseStr = initialResponse instanceof String
                ? (String) initialResponse
                : objectMapper.writeValueAsString(initialResponse);

        Map<String, Object> asyncPoll = (Map<String, Object>) config.get("asyncPoll");

        // Step 2: Extract external task ID
        String idJsonPath = (String) asyncPoll.get("idJsonPath");
        String externalTaskId = asyncTaskPollingService.extractTaskId(initialResponseStr, idJsonPath);
        if (externalTaskId == null || externalTaskId.isEmpty()) {
            String err = "Failed to extract task ID from initial response using path " + idJsonPath;
            log.warn(err);
            Map<String, Object> error = new LinkedHashMap<>();
            error.put("error", err);
            return error;
        }

        // Step 3: Build poll endpoint
        String pollEndpoint = ((String) asyncPoll.get("pollEndpoint")).replace("{id}", externalTaskId);

        // Step 4: Create async task
        AsyncTask task = new AsyncTask();
        task.setSkillId(skill.getId());
        task.setUserId(userId);
        task.setSessionId(sessionId);
        task.setExternalTaskId(externalTaskId);
        task.setPollEndpoint(pollEndpoint);
        task.setPollMethod(asyncPoll.get("pollMethod") instanceof String ? (String) asyncPoll.get("pollMethod") : "GET");
        int pollIntervalSeconds = asyncPoll.get("pollIntervalSeconds") instanceof Number
                ? ((Number) asyncPoll.get("pollIntervalSeconds")).intValue()
                : (asyncPoll.get("pollIntervalMs") instanceof Number
                        ? ((Number) asyncPoll.get("pollIntervalMs")).intValue() / 1000
                        : 5);
        task.setPollIntervalSeconds(pollIntervalSeconds);
        int maxWaitSeconds = asyncPoll.get("maxWaitSeconds") instanceof Number
                ? ((Number) asyncPoll.get("maxWaitSeconds")).intValue()
                : (asyncPoll.get("maxWaitMs") instanceof Number
                        ? ((Number) asyncPoll.get("maxWaitMs")).intValue() / 1000
                        : 600);
        task.setMaxWaitSeconds(maxWaitSeconds);
        if (asyncPoll.get("completionJsonPath") != null) {
            task.setCompletionJsonPath(asyncPoll.get("completionJsonPath").toString());
        }
        if (asyncPoll.get("completionValue") != null) {
            task.setCompletionValue(asyncPoll.get("completionValue").toString());
        }
        if (asyncPoll.get("failedValues") != null) {
            task.setFailedValues(objectMapper.writeValueAsString(asyncPoll.get("failedValues")));
        }
        if (asyncPoll.get("resultJsonPath") != null) {
            task.setResultJsonPath(asyncPoll.get("resultJsonPath").toString());
        }
        if (asyncPoll.get("pollHeaders") != null) {
            task.setPollHeaders(objectMapper.writeValueAsString(asyncPoll.get("pollHeaders")));
        }
        task.setInitialResponse(initialResponseStr);

        asyncTaskPollingService.createTask(task);
        log.info("Created async task {} for skill {} (external={})", task.getId(), skill.getId(), externalTaskId);

        // Step 5: Register future and wait
        java.util.concurrent.CompletableFuture<String> future = asyncTaskPollingScheduler.registerFuture(task.getId());

        try {
            String result = future.get(maxWaitSeconds + 30, java.util.concurrent.TimeUnit.SECONDS);
            return result.startsWith("{") ? objectMapper.readValue(result, Object.class) : result;
        } catch (java.util.concurrent.TimeoutException e) {
            asyncTaskPollingService.updatePollResult(task.getId(), "TIMEOUT", null, "Task timed out after " + maxWaitSeconds + " seconds");
            Map<String, Object> timeout = new LinkedHashMap<>();
            timeout.put("status", "TIMEOUT");
            timeout.put("errorMessage", "Task timed out after " + maxWaitSeconds + " seconds");
            return timeout;
        }
    }
```

**替换为**：

```java
    @SuppressWarnings("unchecked")
    private Object executeApiSkillAsync(Skill skill, Map<String, Object> config, Object parameters, String userId, String sessionId) throws Exception {
        Map<String, Object> asyncPoll = (Map<String, Object>) config.get("asyncPoll");
        String endpoint = (String) config.get("endpoint");
        String method = (String) config.getOrDefault("method", "GET");

        // 读取 pollStrategy，决定后续流程分支
        String pollStrategy = asyncPoll.get("pollStrategy") instanceof String
                ? (String) asyncPoll.get("pollStrategy") : "PERIODIC";
        boolean singleCallMode = "SINGLE_CALL".equals(pollStrategy);

        // ====== SINGLE_CALL 分支：创建 Task → 提交给 singleCallExecutor → 立即返回 ======
        if (singleCallMode) {
            Integer singleCallReadTimeoutSeconds = asyncPoll.get("singleCallReadTimeoutSeconds") instanceof Number
                    ? ((Number) asyncPoll.get("singleCallReadTimeoutSeconds")).intValue() : null;
            if (singleCallReadTimeoutSeconds == null || singleCallReadTimeoutSeconds < 60) {
                singleCallReadTimeoutSeconds = 600;
            }

            // 序列化请求信息，供 Scheduler 回放
            String requestBody = null;
            if (parameters != null) {
                requestBody = parameters instanceof String
                        ? (String) parameters
                        : objectMapper.writeValueAsString(parameters);
            }

            Map<String, Object> configHeaders = (Map<String, Object>) config.get("headers");
            String pollHeadersJson = configHeaders != null ? objectMapper.writeValueAsString(configHeaders) : null;

            AsyncTask task = new AsyncTask();
            task.setSkillId(skill.getId());
            task.setUserId(userId);
            task.setSessionId(sessionId);
            task.setPollStrategy("SINGLE_CALL");
            task.setPollEndpoint(endpoint);
            task.setPollMethod(method);
            task.setRequestBody(requestBody);
            task.setPollHeaders(pollHeadersJson);
            task.setSingleCallReadTimeoutSeconds(singleCallReadTimeoutSeconds);
            task.setStatus("PENDING");

            asyncTaskPollingService.createTask(task);
            log.info("Created SINGLE_CALL async task {} for skill {} (url={}, timeout={}s)",
                    task.getId(), skill.getId(), endpoint, singleCallReadTimeoutSeconds);

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", "SINGLE_CALLED");
            result.put("asyncTaskId", task.getId());
            result.put("note", "Long-running one-shot call submitted (id=" + task.getId() + "). "
                    + "The result will be available in the notification center when the upstream returns. "
                    + "Tell the user the operation is being processed in the background.");
            return result;
        }

        // ====== PERIODIC 分支：调第三方 → extractTaskId → RequestSignature 去重 → 立即返回 ======
        // Step 1: Execute initial API request
        Object initialResponse = executeApiSkill(config, parameters);
        String initialResponseStr = initialResponse instanceof String
                ? (String) initialResponse
                : objectMapper.writeValueAsString(initialResponse);

        // Step 2: Extract external task ID
        String idJsonPath = (String) asyncPoll.get("idJsonPath");
        String externalTaskId = asyncTaskPollingService.extractTaskId(initialResponseStr, idJsonPath);
        if (externalTaskId == null || externalTaskId.isEmpty()) {
            String err = "Failed to extract task ID from initial response using path " + idJsonPath;
            log.warn(err);
            Map<String, Object> error = new LinkedHashMap<>();
            error.put("error", err);
            return error;
        }

        // Step 3: Build poll endpoint
        String pollEndpoint = ((String) asyncPoll.get("pollEndpoint")).replace("{id}", externalTaskId);
        String pollMethod = asyncPoll.get("pollMethod") instanceof String ? (String) asyncPoll.get("pollMethod") : "GET";

        // Step 4: RequestSignature 去重（在创建 AsyncTask 之前）
        String signature = RequestSignatureUtil.compute(method, endpoint, parameters, idJsonPath, pollMethod, pollEndpoint);
        int dedupWindow = sessionId != null && !sessionId.trim().isEmpty()
                ? DedupConfig.PER_SESSION_WINDOW_SECONDS
                : DedupConfig.NO_SESSION_WINDOW_SECONDS;
        AsyncTask duplicate = asyncTaskPollingService.findRecentBySignatureInSession(userId, sessionId, signature, dedupWindow);
        if (duplicate != null) {
            log.info("Dedup hit for async task: existing={} signature={}", duplicate.getId(), signature.substring(0, 16));
            Map<String, Object> dupResult = new LinkedHashMap<>();
            dupResult.put("status", "POLLING");
            dupResult.put("asyncTaskId", duplicate.getId());
            dupResult.put("externalTaskId", duplicate.getExternalTaskId());
            dupResult.put("deduplicated", true);
            dupResult.put("note", "Duplicate request detected. Reusing existing background task (id=" + duplicate.getId() + "). "
                    + "The result will be available in the notification center when complete.");
            return dupResult;
        }

        // Step 5: Create async task
        AsyncTask task = new AsyncTask();
        task.setSkillId(skill.getId());
        task.setUserId(userId);
        task.setSessionId(sessionId);
        task.setExternalTaskId(externalTaskId);
        task.setPollEndpoint(pollEndpoint);
        task.setPollMethod(pollMethod);
        int pollIntervalSeconds = asyncPoll.get("pollIntervalSeconds") instanceof Number
                ? ((Number) asyncPoll.get("pollIntervalSeconds")).intValue()
                : (asyncPoll.get("pollIntervalMs") instanceof Number
                        ? ((Number) asyncPoll.get("pollIntervalMs")).intValue() / 1000
                        : 5);
        task.setPollIntervalSeconds(pollIntervalSeconds);
        int maxWaitSeconds = asyncPoll.get("maxWaitSeconds") instanceof Number
                ? ((Number) asyncPoll.get("maxWaitSeconds")).intValue()
                : (asyncPoll.get("maxWaitMs") instanceof Number
                        ? ((Number) asyncPoll.get("maxWaitMs")).intValue() / 1000
                        : 600);
        task.setMaxWaitSeconds(maxWaitSeconds);
        if (asyncPoll.get("completionJsonPath") != null) {
            task.setCompletionJsonPath(asyncPoll.get("completionJsonPath").toString());
        }
        if (asyncPoll.get("completionValue") != null) {
            task.setCompletionValue(asyncPoll.get("completionValue").toString());
        }
        if (asyncPoll.get("failedValues") != null) {
            task.setFailedValues(objectMapper.writeValueAsString(asyncPoll.get("failedValues")));
        }
        if (asyncPoll.get("resultJsonPath") != null) {
            task.setResultJsonPath(asyncPoll.get("resultJsonPath").toString());
        }
        if (asyncPoll.get("pollHeaders") != null) {
            task.setPollHeaders(objectMapper.writeValueAsString(asyncPoll.get("pollHeaders")));
        }
        task.setInitialResponse(initialResponseStr);
        task.setRequestSignature(signature);

        asyncTaskPollingService.createTask(task);
        log.info("Created PERIODIC async task {} for skill {} (external={})", task.getId(), skill.getId(), externalTaskId);

        // ✅ fire-and-forget: 立即返回，不阻塞 agent（轮询由 AsyncTaskPollingScheduler 后台处理）
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "POLLING");
        result.put("asyncTaskId", task.getId());
        result.put("externalTaskId", externalTaskId);
        result.put("note", "Polling-based async task submitted (id=" + task.getId() + ", external=" + externalTaskId + "). "
                + "The result will be available in the notification center when the polling completes. "
                + "Tell the user the operation is being processed in the background.");
        return result;
    }
```

> **关键变化摘要**：
> - 方法开头新增 `endpoint` / `method` / `pollStrategy` / `singleCallMode` 提取
> - 新增 **SINGLE_CALL 分支**（约 38 行）—— 创建 Task 后立即返回 `{ status: "SINGLE_CALLED" }`
> - PERIODIC 分支新增 **RequestSignature 去重**（约 15 行）
> - `pollMethod` 变量提取以避免重复 `instanceof` 调用
> - 移除 `CompletableFuture` 阻塞等待（删除 `registerFuture` / `future.get` / `TimeoutException` catch）
> - 改为 fire-and-forget 立即返回 `{ status: "POLLING" }`
> - 新增 `task.setRequestSignature(signature)` 调用

---

## 3. AsyncTaskPollingScheduler.java

**文件**：`backend/skill-gateway/src/main/java/com/lobsterai/skillgateway/service/AsyncTaskPollingScheduler.java`

### 3.1 修改 import 区域（第 5-22 行）

**查找**（low-version 原样）：
```java
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
```

**改为**：
```java
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
```

> **变化**：
> - 新增 `import javax.annotation.PreDestroy;`
> - 新增 `import java.net.SocketTimeoutException;`
> - 删除 `import java.time.temporal.ChronoUnit;`
> - 删除 `import java.util.Collections;`
> - 新增 `import java.util.stream.Collectors;`
> - 新增 `import java.util.concurrent.TimeUnit;`
> - import 顺序调整为更规范的排列

### 3.2 替换字段声明（约第 28-58 行）

**查找**（low-version 原样）：
```java
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
```

**改为**：
```java
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
    private final ObjectMapper objectMapper;
```

> **变化**：
> - 新增 `DEFAULT_SINGLE_CALL_READ_TIMEOUT_SECONDS = 600` 常量
> - 删除 `private final ExecutorService executor = Executors.newFixedThreadPool(20);`
> - 新增 `private final ExecutorService periodicExecutor = Executors.newFixedThreadPool(20);`
> - 新增 `private final ExecutorService singleCallExecutor = Executors.newCachedThreadPool();`
> - 删除 `pendingFutures` 字段及其 `registerFuture()` / `completeFuture()` 两个方法

### 3.3 修改 `scheduledScan()` 方法（约第 60-76 行）

**查找**（low-version 原样）：
```java
            log.debug("Polling scheduler picked up {} tasks", tasks.size());

            for (AsyncTask task : tasks) {
                executor.submit(() -> pollSingleTask(task));
            }
```

**改为**：
```java
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
```

> 注意：`log.debug` → `log.info`，且 `executor.submit` → 按 strategy 分发的双线程池。

### 3.4 替换 `pollSingleTask()` 方法的开头部分（第 77-97 行）

**查找**（low-version 原样）：
```java
    private void pollSingleTask(AsyncTask task) {
        AsyncPollingAuditLog startLog = auditService.buildBaseLog(task, "GATEWAY_POLL_START");
        startLog.setExtraJson(auditService.safeJson(Collections.singletonMap("retryCount", task.getPollRetryCount() != null ? task.getPollRetryCount() : 0)));
        auditService.log(startLog);

        try {
            if (task.getPollEndpoint() == null || task.getPollEndpoint().trim().isEmpty()) {
                return;
            }

            String status = task.getStatus();
            if (!"PENDING".equals(status) && !"POLLING".equals(status)) {
                return;
            }

            if ("PENDING".equals(status)) {
                pollingService.updateStatusAndLastPolled(task.getId(), "POLLING");
            }
```

**改为**：
```java
    private void pollSingleTask(AsyncTask task) {
        boolean singleCallMode = "SINGLE_CALL".equals(task.getPollStrategy());

        AsyncPollingAuditLog startLog = auditService.buildBaseLog(task, "GATEWAY_POLL_START");
        Map<String, Object> extra = new HashMap<>();
        extra.put("retryCount", task.getPollRetryCount() != null ? task.getPollRetryCount() : 0);
        extra.put("pollStrategy", task.getPollStrategy() != null ? task.getPollStrategy() : "PERIODIC");
        startLog.setExtraJson(auditService.safeJson(extra));
        auditService.log(startLog);

        try {
            if (task.getPollEndpoint() == null || task.getPollEndpoint().trim().isEmpty()) {
                return;
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
```

> **变化**：
> - 新增 `boolean singleCallMode` 
> - `Collections.singletonMap` → `new HashMap<>()` 并新增 `pollStrategy` 字段
> - 状态过滤新增 `!"SINGLE_CALLED".equals(status)`
> - PENDING 状态转换按 singleCallMode 分支处理

### 3.5 替换 pollHeaders 解析后的请求构造部分

**查找**（low-version 中 pollHeaders 解析之后的 `Object pollResponse;` 开始到 `try {` 内的请求调用）：

```java
            Object pollResponse;
            long networkStart = System.currentTimeMillis();
            try {
                pollResponse = apiProxyService.callApi(
                        task.getPollEndpoint(),
                        task.getPollMethod() != null ? task.getPollMethod().toUpperCase() : "GET",
                        pollHeaders,
                        null
                );
```

**改为**：
```java
            // ============== 构造请求 ==============

            int readTimeoutSeconds;
            String requestUrl;
            if (singleCallMode) {
                readTimeoutSeconds = task.getSingleCallReadTimeoutSeconds() != null
                        ? task.getSingleCallReadTimeoutSeconds()
                        : (task.getMaxWaitSeconds() != null ? task.getMaxWaitSeconds() : DEFAULT_SINGLE_CALL_READ_TIMEOUT_SECONDS);
                requestUrl = task.getPollEndpoint();
                if (requestUrl == null || requestUrl.trim().isEmpty()) {
                    String err = "SINGLE_CALL task must have pollEndpoint (reused as long-call URL)";
                    pollingService.updatePollResult(task.getId(), "FAILED", null, err);
                    auditService.log(buildCompleteLog(task, "FAILED", err));
                    return;
                }
            } else {
                readTimeoutSeconds = 30;
                requestUrl = task.getPollEndpoint();
            }

            Object pollResponse;
            long networkStart = System.currentTimeMillis();
            try {
                if (singleCallMode) {
                    Object requestBody = null;
                    if (task.getRequestBody() != null && !task.getRequestBody().trim().isEmpty()) {
                        try {
                            requestBody = objectMapper.readValue(task.getRequestBody(), Object.class);
                        } catch (Exception bodyParseEx) {
                            log.warn("Failed to deserialize requestBody for SINGLE_CALL task {}: {}",
                                    task.getId(), bodyParseEx.getMessage());
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
                    pollResponse = apiProxyService.callApi(
                            requestUrl,
                            task.getPollMethod() != null ? task.getPollMethod().toUpperCase() : "GET",
                            pollHeaders,
                            null
                    );
                }
```

> **变化**：
> - 新增 `readTimeoutSeconds` / `requestUrl` 变量 + SINGLE_CALL 分支构造逻辑
> - SINGLE_CALL 走 5 参数重载（带 `requestBody` + `readTimeoutSeconds`）
> - PERIODIC 走 4 参数重载（行为不变），但 URL 变量从 `task.getPollEndpoint()` 改为 `requestUrl`

### 3.6 修改审计日志中的 URL（两处）

**查找**（两处）：
```java
                netLog.setHttpUrl(task.getPollEndpoint());
```

**全部改为**：
```java
                netLog.setHttpUrl(requestUrl);
```

同样地，`log.debug("Polled...")` 行中 `task.getPollEndpoint()` → `requestUrl`。

### 3.7 在成功响应日志后、`} catch` 之前插入 SINGLE_CALL 终态处理

**查找**（紧接在 `responseStr.substring(0, Math.min(200, ...)));` 之后，`} catch (Exception netEx)` 之前）插入以下代码块：

```java

                // ============== SINGLE_CALL 模式：拿到响应即 COMPLETED ==============
                if (singleCallMode) {
                    String result = pollingService.extractResult(responseStr, task.getResultJsonPath());
                    pollingService.updatePollResult(task.getId(), "COMPLETED", result, null);
                    log.info("Async task {} (SINGLE_CALL) completed after {}ms", task.getId(), durationMs);

                    AsyncPollingAuditLog completeLog = auditService.buildBaseLog(task, "GATEWAY_POLL_COMPLETE");
                    completeLog.setStatus("COMPLETED");
                    completeLog.setDurationMs((int) durationMs);
                    auditService.log(completeLog);
                    return;
                }
```

### 3.8 在 `catch (Exception netEx)` 之后、`auditService.log(netErrLog);` 之后插入

**查找**（在 `auditService.log(netErrLog);` 之后、下一个空行之前插入）：

```java

                if (singleCallMode) {
                    if (netEx instanceof SocketTimeoutException) {
                        String err = "SINGLE_CALL read timeout after " + readTimeoutSeconds + "s";
                        pollingService.updatePollResult(task.getId(), "TIMEOUT", null, err);
                        log.info("Async task {} (SINGLE_CALL) timed out after {}ms", task.getId(), durationMs);
                        AsyncPollingAuditLog timeoutLog = auditService.buildBaseLog(task, "GATEWAY_POLL_COMPLETE");
                        timeoutLog.setStatus("TIMEOUT");
                        timeoutLog.setErrorMessage(err);
                        timeoutLog.setDurationMs((int) durationMs);
                        auditService.log(timeoutLog);
                    } else {
                        String err = "SINGLE_CALL network error: " + netEx.getMessage();
                        pollingService.updatePollResult(task.getId(), "FAILED", null, err);
                        log.warn("Async task {} (SINGLE_CALL) failed: {}", task.getId(), netEx.getMessage());
                        auditService.log(buildCompleteLog(task, "FAILED", err));
                    }
                    return;
                }
```

### 3.9 在原 `throw netEx;` 之前加注释

**查找**（在 `throw netEx;` 之前插入一行）：

```java
                // PERIODIC：转抛给外层 catch 走 retry 逻辑
```

使其变成：
```java
                // PERIODIC：转抛给外层 catch 走 retry 逻辑
                throw netEx;
```

### 3.10 删除所有 `completeFuture(...)` 调用（共 4 处）

在方法的 PERIODIC 终态处理部分，找到并删除以下 4 行：

```java
                completeFuture(task.getId(), result != null ? result : pollResponseStr);
```
```java
                completeFuture(task.getId(), "{\"status\":\"FAILED\",\"errorMessage\":\"" + errMsg + "\"}");
```
```java
                completeFuture(task.getId(), "{\"status\":\"TIMEOUT\",\"errorMessage\":\"" + errMsg + "\"}");
```
```java
                completeFuture(task.getId(), "{\"status\":\"FAILED\",\"errorMessage\":\"" + errMsg + "\"}");
```

### 3.11 在类末尾（最后一个 `}` 之前）添加两个新方法

**在类的最后一个 `}` 之前**（即 `pollingService.updateStatus(...)` 所在的 catch 块之后）添加：

```java

    private AsyncPollingAuditLog buildCompleteLog(AsyncTask task, String status, String errMsg) {
        AsyncPollingAuditLog log = auditService.buildBaseLog(task, "GATEWAY_POLL_COMPLETE");
        log.setStatus(status);
        if (errMsg != null) log.setErrorMessage(errMsg);
        return log;
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
```

---

## 4. SystemSkillController.java

**文件**：`backend/skill-gateway/src/main/java/com/lobsterai/skillgateway/controller/SystemSkillController.java`

### 4.1 在 `props.put("parameterContract", pc);` 之后插入 asyncPoll 定义

**查找**：
```java
        props.put("parameterContract", pc);
```

在它**之后**、`Map<String, Object> asyncPollEnabled` **之前**插入：

```java

        Map<String, Object> asyncPoll = new LinkedHashMap<>();
        asyncPoll.put("type", "object");
        asyncPoll.put("label", "异步轮询配置");
        asyncPoll.put("ui", "jsonEditor");
        Map<String, Object> pollAiOpt = new LinkedHashMap<>();
        pollAiOpt.put("fieldId", "api_async_poll");
        asyncPoll.put("aiOptimize", pollAiOpt);
        asyncPoll.put("aiHint", "启用后 Gateway 内部完成轮询闭环。示例: {\"pollEndpoint\":\"...\",\"idJsonPath\":\"$.taskId\",\"completionJsonPath\":\"$.status\",\"completionValue\":\"COMPLETED\"}");
        Map<String, Object> pollVisible = new LinkedHashMap<>();
        pollVisible.put("field", "asyncPollEnabled");
        pollVisible.put("equals", true);
        asyncPoll.put("visibleWhen", pollVisible);
        props.put("asyncPoll", asyncPoll);

```

### 4.2 删除 `asyncPollEnabled` 的 `aiHint` 行

**查找并删除**：
```java
        asyncPollEnabled.put("aiHint", "适用于上游 API 返回 task_id 后需要轮询结果的场景");
```

### 4.3 替换 asyncPollStrategy 定义块

**查找**（low-version 原样）：
```java
        Map<String, Object> asyncPollStrategy = new LinkedHashMap<>();
        asyncPollStrategy.put("type", "string");
        asyncPollStrategy.put("label", "轮询策略");
        asyncPollStrategy.put("ui", "radio");
        asyncPollStrategy.put("default", "PERIODIC");
        asyncPollStrategy.put("enum", java.util.Arrays.asList("PERIODIC", "SINGLE_CALL"));
        Map<String, Object> strategyVisibleWhen = new LinkedHashMap<>();
        strategyVisibleWhen.put("field", "asyncPollEnabled");
        strategyVisibleWhen.put("equals", true);
        asyncPollStrategy.put("visibleWhen", strategyVisibleWhen);
        asyncPollStrategy.put("aiHint", "PERIODIC=周期轮询, SINGLE_CALL=单次长调用");
        props.put("asyncPollStrategy", asyncPollStrategy);
```

**改为**：
```java
        // asyncPollStrategy: 轮询策略选择（PERIODIC / SINGLE_CALL）
        Map<String, Object> asyncPollStrategy = new LinkedHashMap<>();
        asyncPollStrategy.put("type", "string");
        asyncPollStrategy.put("label", "轮询策略");
        asyncPollStrategy.put("ui", "radio");
        asyncPollStrategy.put("default", "PERIODIC");
        Map<String, Object> strategyVisible = new LinkedHashMap<>();
        strategyVisible.put("field", "asyncPollEnabled");
        strategyVisible.put("equals", true);
        asyncPollStrategy.put("visibleWhen", strategyVisible);
        asyncPollStrategy.put("enum", java.util.Arrays.asList("PERIODIC", "SINGLE_CALL"));
        asyncPollStrategy.put("enumLabels", java.util.Arrays.asList(
                "周期轮询（需要提供状态查询端点 + {id} 占位符）",
                "单次长调用（无需 pollEndpoint，提交后立即返回，长 readTimeout 等结果）"));
        props.put("asyncPollStrategy", asyncPollStrategy);
```

> 注意：
> - `strategyVisibleWhen` → `strategyVisible`
> - 删除 `aiHint` 行
> - 新增 `enumLabels` 数组
> - `enum` 行移到 `visibleWhen` 之后

### 4.4 替换 asyncPollReadTimeout 定义块

**查找**（low-version 原样）：
```java
        Map<String, Object> asyncPollReadTimeout = new LinkedHashMap<>();
        asyncPollReadTimeout.put("type", "number");
        asyncPollReadTimeout.put("label", "单次调用 read timeout（秒）");
        asyncPollReadTimeout.put("ui", "number");
        asyncPollReadTimeout.put("default", 600);
        asyncPollReadTimeout.put("minimum", 1);
        asyncPollReadTimeout.put("maximum", 3600);
        Map<String, Object> timeoutVisibleWhen = new LinkedHashMap<>();
        timeoutVisibleWhen.put("field", "asyncPollStrategy");
        timeoutVisibleWhen.put("equals", "SINGLE_CALL");
        asyncPollReadTimeout.put("visibleWhen", timeoutVisibleWhen);
        asyncPollReadTimeout.put("aiHint", "单次调用的最大等待时间，到达后由后台线程继续等待");
        props.put("asyncPollReadTimeoutSeconds", asyncPollReadTimeout);
```

**改为**：
```java
        // asyncPollReadTimeoutSeconds: SINGLE_CALL 模式的 read timeout（秒）
        Map<String, Object> asyncPollReadTimeout = new LinkedHashMap<>();
        asyncPollReadTimeout.put("type", "number");
        asyncPollReadTimeout.put("label", "单次调用 read timeout（秒）");
        asyncPollReadTimeout.put("ui", "input");
        asyncPollReadTimeout.put("default", 600);
        Map<String, Object> timeoutVisible = new LinkedHashMap<>();
        timeoutVisible.put("field", "asyncPollStrategy");
        timeoutVisible.put("equals", "SINGLE_CALL");
        asyncPollReadTimeout.put("visibleWhen", timeoutVisible);
        props.put("asyncPollReadTimeoutSeconds", asyncPollReadTimeout);
```

> 变化：
> - `"ui", "number"` → `"ui", "input"`
> - 删除 `minimum` / `maximum` 约束
> - `timeoutVisibleWhen` → `timeoutVisible`
> - 删除 `aiHint` 行

### 4.5 删除文件末尾的 asyncPoll 定义块

**查找并完整删除**（low-version 中在 `props.put("asyncPollReadTimeoutSeconds", ...)` 之后的 asyncPoll 定义）：

```java
        Map<String, Object> asyncPoll = new LinkedHashMap<>();
        asyncPoll.put("type", "object");
        asyncPoll.put("label", "异步轮询配置 (JSON)");
        asyncPoll.put("ui", "jsonEditor");
        Map<String, Object> pollAiOpt = new LinkedHashMap<>();
        pollAiOpt.put("fieldId", "api_async_poll");
        asyncPoll.put("aiOptimize", pollAiOpt);
        Map<String, Object> pollVisibleWhen = new LinkedHashMap<>();
        pollVisibleWhen.put("field", "asyncPollStrategy");
        pollVisibleWhen.put("equals", "PERIODIC");
        asyncPoll.put("visibleWhen", pollVisibleWhen);
        asyncPoll.put("aiHint", "启用后 Gateway 内部完成轮询闭环。示例: {\"pollEndpoint\":\"...\",\"idJsonPath\":\"$.taskId\",\"completionJsonPath\":\"$.status\",\"completionValue\":\"COMPLETED\"}");
        props.put("asyncPoll", asyncPoll);
```

> **注意**：这段被移到了 `parameterContract` 之后（见 4.1），且 `visibleWhen` 条件从 `asyncPollStrategy === PERIODIC` 改为 `asyncPollEnabled === true`，label 从 `"异步轮询配置 (JSON)"` 改为 `"异步轮询配置"`。

---

## 5. schema-mysql.sql

**文件**：`backend/skill-gateway/src/main/resources/schema-mysql.sql`

### 5.1 skills 表删除 `schema_properties` 列

**查找**（约第 22-24 行）：
```sql
    updated_at DATETIME,
    schema_properties TEXT
```

**改为**：
```sql
    updated_at DATETIME
```

### 5.2 async_tasks 表新增 `session_id` 列

**查找**（在 `user_id VARCHAR(64),` 之后）：
```sql
    user_id VARCHAR(64),
    external_task_id VARCHAR(255),
```

**改为**：
```sql
    user_id VARCHAR(64),
    session_id VARCHAR(64),
    external_task_id VARCHAR(255),
```

### 5.3 索引重命名

**查找**（约第 157 行）：
```sql
    INDEX idx_async_user_sig_time (user_id, request_signature, created_at)
```

**改为**：
```sql
    INDEX idx_async_user_session_sig_time (user_id, session_id, request_signature, created_at)
```

---

## 6. schema-h2.sql（main）

**文件**：`backend/skill-gateway/src/main/resources/schema-h2.sql`

**查找**（约第 45-48 行）：
```sql
    UNIQUE KEY uk_server_ledgers_user_name (user_id, name),
    UNIQUE KEY uk_server_ledgers_user_host (user_id, host)
```

**改为**：
```sql
    UNIQUE (user_id, name),
    UNIQUE (user_id, host)
```

---

## 7. schema-h2.sql（test）

**文件**：`backend/skill-gateway/src/test/resources/schema-h2.sql`

**同上**，完全相同的修改：
```sql
    UNIQUE KEY uk_server_ledgers_user_name (user_id, name),
    UNIQUE KEY uk_server_ledgers_user_host (user_id, host)
```
→
```sql
    UNIQUE (user_id, name),
    UNIQUE (user_id, host)
```

---

## 8. schema.sql（Migration 脚本）

**文件**：`backend/skill-gateway/src/main/resources/schema.sql`

### 8.1 缩进修复（约第 17-18 行）

**查找**：
```sql
 EXECUTE stmt;
 DEALLOCATE PREPARE stmt;
```

**改为**（删除行首空格）：
```sql
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
```

### 8.2 删除 schema_properties 动态添加逻辑（文件末尾约 13 行）

**查找并完整删除**（约在文件末尾 `DROP TABLE IF EXISTS agent_core_invocation_audit_logs;` 之后）：

```sql

-- skills.schema_properties（持久化计算的 schema 属性，供 Agent 列表接口直接使用）
SET @sql = (
  SELECT IF(
    (SELECT COUNT(*) FROM information_schema.columns
     WHERE table_schema = @db AND table_name = 'skills' AND column_name = 'schema_properties') > 0,
    'SELECT 1',
    'ALTER TABLE skills ADD COLUMN schema_properties TEXT'
  )
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
```

---

## 9. application.properties（从 .example 重命名）

**操作**：
1. 将 `backend/skill-gateway/src/main/resources/application.properties.example` 重命名为 `application.properties`
2. 修改内容如下：

**low-version 的 `.example` 文件内容**（约 61 行，包含注释和占位符 `YOUR_LOCAL_MYSQL_PASSWORD`）：

替换 `spring.datasource.password=YOUR_LOCAL_MYSQL_PASSWORD` 为实际密码 `spring.datasource.password=52415241`，并删除 `allowPublicKeyRetrieval=true`。

**temp 版本内容**（完整）：
```properties
spring.datasource.url=jdbc:mysql://127.0.0.1:3306/fishtank?useUnicode=true&characterEncoding=UTF-8&connectionCollation=utf8mb4_unicode_ci&serverTimezone=UTC
spring.datasource.driver-class-name=com.mysql.cj.jdbc.Driver
spring.datasource.username=root
spring.datasource.password=52415241
spring.sql.init.mode=always
spring.sql.init.schema-locations=classpath:schema-mysql.sql

# MyBatis-Plus
mybatis-plus.mapper-locations=classpath*:/mapper/**/*.xml
mybatis-plus.type-aliases-package=com.lobsterai.skillgateway.entity
mybatis-plus.configuration.log-impl=org.apache.ibatis.logging.stdout.StdOutImpl
mybatis-plus.global-config.db-config.id-type=auto
mybatis-plus.type-enums-package=com.lobsterai.skillgateway.entity

# 平台 Skill 行（createdBy=public）的写操作仅允许用户 ID 890728（见 SkillService.SKILL_PLATFORM_ADMIN_USER_ID，非配置项）

# Disable async request timeout for SSE streams (async polling can idle for minutes)
spring.mvc.async.request-timeout=-1

# Gateway listener
server.address=0.0.0.0
server.port=18080

# Upstream agent-core service
agent.core.url=http://localhost:3000

# Registration gate: only clients providing this password may create new users (override in prod if needed)
app.registration.admin-password=Bxdc1357

# Frontend origins allowed by CORS (comma-separated, supports patterns like http://localhost:*)
app.cors.allowed-origins=http://localhost:*,http://127.0.0.1:*

# LLM 原始 HTTP 审计（agent-core POST /api/internal/llm-http-audit/events）单条 JSON 最大字节数
app.llm-http-audit.max-payload-bytes=1048576

# Skill 对外 HTTP/SSH 审计与 agent-core 回调审计（单字段最大字节；逗号分隔的额外脱敏 header 名）
app.gateway-audit.max-payload-bytes=1048576
app.gateway-audit.redacted-headers=

# Linux script built-in skill server registry examples
# Linux 脚本与 SSH（台账模式）：连接信息存于 server_ledgers 表，不再使用下方按名映射。
# skill.linux-script.servers.* 已废弃（若存在旧文档可删除）。
# skill.linux-script.servers.demo.host=127.0.0.1
# skill.linux-script.servers.demo.port=22
# skill.linux-script.servers.demo.username=root
# skill.linux-script.servers.demo.private-key-path=/path/to/private_key
```

> **与 low-version 的 `.example` 文件区别**：
> - `password=YOUR_LOCAL_MYSQL_PASSWORD` → `password=52415241`
> - `allowPublicKeyRetrieval=true&` 已从 URL 中删除
> - 文件头部的注释块已删除（`# ⚠️ 本文件是本地开发配置模板...`）
