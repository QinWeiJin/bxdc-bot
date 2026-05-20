package com.lobsterai.skillgateway.controller;

import com.lobsterai.skillgateway.entity.Skill;
import com.lobsterai.skillgateway.service.AsyncTaskPollingService;
import com.lobsterai.skillgateway.service.BuiltinToolExecutionService;
import com.lobsterai.skillgateway.service.GatewayOutboundAuditService;
import com.lobsterai.skillgateway.service.LinuxScriptExecutionService;
import com.lobsterai.skillgateway.service.ServerLedgerService;
import com.lobsterai.skillgateway.service.SkillService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.lobsterai.skillgateway.entity.AsyncTask;
import com.lobsterai.skillgateway.entity.AsyncPollingAuditLog;
import com.lobsterai.skillgateway.entity.ServerLedger;
import com.lobsterai.skillgateway.entity.SkillTextPrompt;
import com.lobsterai.skillgateway.mapper.SkillTextPromptMapper;
import com.lobsterai.skillgateway.service.ApiProxyService;
import com.lobsterai.skillgateway.service.AsyncPollingAuditService;
import com.lobsterai.skillgateway.util.JsonPathUtils;

/**
 * Skill 控制器。
 * <p>
 * 暴露 RESTful 接口供 Agent Core 调用，以执行具体的 SSH 命令或 API 请求。
 * 包含安全检查逻辑。
 * 此外，提供 Skill 的统一管理（CRUD）。
 * </p>
 */
@RestController
@RequestMapping("/api/skills")
public class SkillController {

    private final SkillService skillService;
    private final LinuxScriptExecutionService linuxScriptExecutionService;
    private final ServerLedgerService serverLedgerService;
    private final BuiltinToolExecutionService builtinToolExecutionService;
    private final GatewayOutboundAuditService gatewayOutboundAuditService;
    private final AsyncTaskPollingService asyncTaskPollingService;
    private final SkillTextPromptMapper skillTextPromptMapper;
    private final ApiProxyService apiProxyService;
    private final AsyncPollingAuditService pollingAuditService;
    private final ObjectMapper objectMapper;

    public SkillController(
            SkillService skillService,
            LinuxScriptExecutionService linuxScriptExecutionService,
            ServerLedgerService serverLedgerService,
            BuiltinToolExecutionService builtinToolExecutionService,
            GatewayOutboundAuditService gatewayOutboundAuditService,
            AsyncTaskPollingService asyncTaskPollingService,
            SkillTextPromptMapper skillTextPromptMapper,
            ApiProxyService apiProxyService,
            AsyncPollingAuditService pollingAuditService,
            ObjectMapper objectMapper
    ) {
        this.skillService = skillService;
        this.linuxScriptExecutionService = linuxScriptExecutionService;
        this.serverLedgerService = serverLedgerService;
        this.builtinToolExecutionService = builtinToolExecutionService;
        this.gatewayOutboundAuditService = gatewayOutboundAuditService;
        this.asyncTaskPollingService = asyncTaskPollingService;
        this.skillTextPromptMapper = skillTextPromptMapper;
        this.apiProxyService = apiProxyService;
        this.pollingAuditService = pollingAuditService;
        this.objectMapper = objectMapper;
    }

    // --- Skill Management (CRUD) ---

    @GetMapping
    public List<Skill> getAllSkills(
            @RequestHeader(value = "X-User-Id", required = false) String userId
    ) {
        return skillService.listSkillsForUser(userId);
    }

    @GetMapping("/{id}")
    public ResponseEntity<Skill> getSkillById(
            @PathVariable Long id,
            @RequestHeader(value = "X-User-Id", required = false) String userId
    ) {
        return skillService.getSkillByIdForUser(id, userId)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping
    public ResponseEntity<?> createSkill(
            @RequestBody Skill skill,
            @RequestHeader(value = "X-User-Id", required = false) String userId
    ) {
        try {
            return ResponseEntity.ok(skillService.createSkill(skill, userId));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PutMapping("/{id}")
    public ResponseEntity<?> updateSkill(
            @PathVariable Long id,
            @RequestBody Skill skillDetails,
            @RequestHeader(value = "X-User-Id", required = false) String userId
    ) {
        try {
            return ResponseEntity.ok(skillService.updateSkill(id, skillDetails, userId));
        } catch (IllegalArgumentException e) {
            if (e.getMessage() != null && e.getMessage().startsWith("Skill not found")) {
                return ResponseEntity.notFound().build();
            }
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> deleteSkill(
            @PathVariable Long id,
            @RequestHeader(value = "X-User-Id", required = false) String userId
    ) {
        try {
            skillService.deleteSkill(id, userId);
            return ResponseEntity.ok().build();
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }

    @GetMapping("/server-lookup")
    public ResponseEntity<?> lookupServer(
            @RequestHeader(value = "X-User-Id", required = false) String userId,
            @RequestParam(value = "serverName", required = false) String serverName,
            @RequestParam(value = "name", required = false) String name
    ) {
        if (userId == null || userId.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "X-User-Id header is required for server lookup"));
        }
        String q = (serverName != null && !serverName.isBlank()) ? serverName : name;
        if (q == null || q.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "serverName (or legacy name) query parameter is required"));
        }
        List<ServerLedgerService.ServerNameCandidate> candidates = serverLedgerService.findTopServerNameMatches(userId, q, 5);
        List<Map<String, Object>> list = new java.util.ArrayList<>();
        for (ServerLedgerService.ServerNameCandidate c : candidates) {
            java.util.Map<String, Object> row = new java.util.LinkedHashMap<>();
            row.put("id", c.id());
            row.put("name", c.name());
            list.add(row);
        }
        int n = list.size();
        return ResponseEntity.ok(Map.of(
                "candidates", list,
                "count", n,
                "needsUserConfirmation", n > 1
        ));
    }

    // --- Skill Execution ---

    /**
     * 执行 SSH 命令。
     *
     * @param request 包含主机、端口、认证信息和命令的请求体
     * @return 命令执行结果或错误信息
     */
    @PostMapping("/ssh")
    public ResponseEntity<String> executeSshCommand(
            @RequestHeader(value = "X-User-Id", required = false) String userId,
            @RequestBody SshRequest request
    ) {
        return builtinToolExecutionService.executeSsh(request, userId);
    }

    /**
     * 调用外部 API。
     *
     * @param request 包含 URL、方法、头信息和请求体的请求对象
     * @return 外部 API 的响应
     */
    @PostMapping("/api")
    public ResponseEntity<Object> callApi(@RequestBody ApiRequest request) {
        try {
            Object response = builtinToolExecutionService.callExternalApi(request);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body("API call failed: " + e.getMessage());
        }
    }

    @PostMapping("/api/async")
    public ResponseEntity<?> callApiAsync(
            @RequestHeader(value = "X-User-Id", required = false) String userId,
            @RequestHeader(value = "X-Skill-Id", required = false) Long skillId,
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId,
            @RequestBody ApiRequest request
    ) {
        try {
            Map<String, Object> asyncPoll = request.getAsyncPoll();
            if (asyncPoll == null || !asyncPoll.containsKey("pollEndpoint")) {
                return ResponseEntity.badRequest().body(Map.of("error", "asyncPoll.pollEndpoint is required for async API calls"));
            }

            int timeoutSeconds = request.getTimeoutSeconds() != null ? request.getTimeoutSeconds() : 30;
            Object initialResponse = builtinToolExecutionService.callExternalApi(request);

            String initialResponseStr = initialResponse instanceof String
                    ? (String) initialResponse
                    : objectMapper.writeValueAsString(initialResponse);

            String idJsonPath = (String) asyncPoll.get("idJsonPath");
            String externalTaskId = asyncTaskPollingService.extractTaskId(initialResponseStr, idJsonPath);
            if (externalTaskId == null || externalTaskId.isBlank()) {
                return ResponseEntity.badRequest().body(Map.of(
                        "error", "Failed to extract task id from initial response",
                        "idJsonPath", idJsonPath,
                        "initialResponse", initialResponseStr
                ));
            }

            String pollEndpoint = ((String) asyncPoll.get("pollEndpoint")).replace("{id}", externalTaskId);

            if (!((String) asyncPoll.get("pollEndpoint")).contains("{id}")) {
                return ResponseEntity.badRequest().body(Map.of(
                        "error", "asyncPoll.pollEndpoint must contain {id} placeholder",
                        "hint", "The {id} placeholder is replaced with the extracted task ID. Example: /status?task_id={id}"
                ));
            }
            int pollIntervalSeconds = asyncPoll.get("pollIntervalSeconds") instanceof Number
                    ? ((Number) asyncPoll.get("pollIntervalSeconds")).intValue()
                    : (asyncPoll.get("pollIntervalMs") instanceof Number
                            ? Math.max(1, ((Number) asyncPoll.get("pollIntervalMs")).intValue() / 1000)
                            : 5);
            int maxWaitSeconds = asyncPoll.get("maxWaitSeconds") instanceof Number
                    ? ((Number) asyncPoll.get("maxWaitSeconds")).intValue()
                    : (asyncPoll.get("maxWaitMs") instanceof Number
                            ? Math.max(1, ((Number) asyncPoll.get("maxWaitMs")).intValue() / 1000)
                            : 600);

            AsyncTask task = new AsyncTask();
            task.setSkillId(skillId);
            task.setUserId(userId);
            task.setSessionId(sessionId);
            task.setExternalTaskId(externalTaskId);
            task.setPollEndpoint(pollEndpoint);
            task.setPollMethod(asyncPoll.get("pollMethod") instanceof String ? (String) asyncPoll.get("pollMethod") : "GET");
            task.setPollIntervalSeconds(pollIntervalSeconds);
            task.setMaxWaitSeconds(maxWaitSeconds);
            task.setCompletionJsonPath((String) asyncPoll.get("completionJsonPath"));
            task.setCompletionValue((String) asyncPoll.get("completionValue"));
            task.setResultJsonPath((String) asyncPoll.get("resultJsonPath"));

            if (asyncPoll.get("failedValues") != null) {
                task.setFailedValues(objectMapper.writeValueAsString(asyncPoll.get("failedValues")));
            }
            if (asyncPoll.get("pollHeaders") != null) {
                task.setPollHeaders(objectMapper.writeValueAsString(asyncPoll.get("pollHeaders")));
            }
            task.setInitialResponse(initialResponseStr);

            asyncTaskPollingService.createTask(task);

            return ResponseEntity.ok(Map.of(
                    "asyncTaskId", task.getId(),
                    "status", "PENDING",
                    "externalTaskId", externalTaskId
            ));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("error", "Async API call failed: " + e.getMessage()));
        }
    }

    @GetMapping("/async-tasks/{id}/wait")
    public ResponseEntity<?> waitForAsyncTask(
            @PathVariable Long id,
            @RequestParam(defaultValue = "300000") long timeoutMs
    ) {
        long deadline = System.currentTimeMillis() + timeoutMs;

        while (System.currentTimeMillis() < deadline) {
            AsyncTask task = asyncTaskPollingService.findById(id);
            if (task == null) {
                return ResponseEntity.notFound().build();
            }

            String status = task.getStatus();
            if ("COMPLETED".equals(status)) {
                return ResponseEntity.ok(Map.of(
                        "status", "COMPLETED",
                        "result", (Object) task.getPollResult()
                ));
            }
            if ("FAILED".equals(status)) {
                return ResponseEntity.ok(Map.of(
                        "status", "FAILED",
                        "errorMessage", task.getErrorMessage() != null ? task.getErrorMessage() : "Task failed"
                ));
            }
            if ("TIMEOUT".equals(status)) {
                return ResponseEntity.ok(Map.of(
                        "status", "TIMEOUT",
                        "errorMessage", task.getErrorMessage() != null ? task.getErrorMessage() : "Task timed out"
                ));
            }

            try {
                Thread.sleep(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return ResponseEntity.ok(Map.of(
                        "status", task.getStatus(),
                        "result", null
                ));
            }
        }

        AsyncTask task = asyncTaskPollingService.findById(id);
        return ResponseEntity.ok(Map.of(
                "status", task != null ? task.getStatus() : "UNKNOWN",
                "result", null
        ));
    }

    // --- Text Prompts (AI optimization) ---

    @GetMapping("/text-prompts")
    public List<SkillTextPrompt> getAllTextPrompts() {
        return skillTextPromptMapper.selectList(null);
    }

    @GetMapping("/text-prompts/{fieldId}")
    public ResponseEntity<SkillTextPrompt> getTextPrompt(@PathVariable String fieldId) {
        return skillTextPromptMapper.findByFieldId(fieldId)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PutMapping("/text-prompts/{fieldId}")
    public ResponseEntity<?> updateTextPrompt(
            @PathVariable String fieldId,
            @RequestHeader(value = "X-User-Id", required = false) String userId,
            @RequestBody SkillTextPrompt body
    ) {
        return skillTextPromptMapper.findByFieldId(fieldId)
                .map(existing -> {
                    if (body.getSystemPrompt() != null) existing.setSystemPrompt(body.getSystemPrompt());
                    if (body.getUserPromptTemplate() != null) existing.setUserPromptTemplate(body.getUserPromptTemplate());
                    skillTextPromptMapper.updateById(existing);
                    return ResponseEntity.ok(existing);
                })
                .orElse(ResponseEntity.notFound().build());
    }

    // --- Enum Source (dynamic dropdown options) ---

    @PostMapping("/enum-source")
    public ResponseEntity<?> fetchEnumSource(@RequestBody Map<String, Object> body) {
        try {
            String url = (String) body.get("url");
            if (url == null || url.isBlank()) {
                return ResponseEntity.badRequest().body(Map.of("error", "url is required"));
            }
            String method = body.get("method") instanceof String ? (String) body.get("method") : "GET";
            @SuppressWarnings("unchecked")
            Map<String, Object> headers = body.get("headers") instanceof Map ? (Map<String, Object>) body.get("headers") : null;
            String jsonPath = (String) body.get("jsonPath");
            String valueKey = body.get("valueKey") instanceof String ? (String) body.get("valueKey") : "value";
            String labelKey = body.get("labelKey") instanceof String ? (String) body.get("labelKey") : "label";
            String searchParam = (String) body.get("searchParam");
            String searchQuery = (String) body.get("searchQuery");

            String resolvedUrl = url;
            if (searchQuery != null && !searchQuery.isBlank() && searchParam != null && !searchParam.isBlank()) {
                String separator = resolvedUrl.contains("?") ? "&" : "?";
                resolvedUrl += separator + searchParam + "=" + java.net.URLEncoder.encode(searchQuery, "UTF-8");
            }

            Object response = apiProxyService.callApi(resolvedUrl, method, headers, null);
            String responseStr = response instanceof String ? (String) response : objectMapper.writeValueAsString(response);
            Object parsed = objectMapper.readValue(responseStr, Object.class);

            Object listNode = (jsonPath != null && !jsonPath.isBlank())
                    ? JsonPathUtils.extractValueByPath(parsed, jsonPath)
                    : parsed;

            if (!(listNode instanceof List)) {
                return ResponseEntity.badRequest().body(Map.of(
                        "error", "jsonPath did not resolve to an array",
                        "jsonPath", jsonPath,
                        "responsePreview", responseStr.substring(0, Math.min(500, responseStr.length()))
                ));
            }

            @SuppressWarnings("unchecked")
            List<Object> items = (List<Object>) listNode;
            List<Map<String, String>> options = new java.util.ArrayList<>();
            for (Object item : items) {
                if (!(item instanceof Map)) continue;
                @SuppressWarnings("unchecked")
                Map<String, Object> map = (Map<String, Object>) item;
                Object labelObj = map.get(labelKey);
                Object valueObj = map.get(valueKey);
                if (valueObj != null) {
                    options.add(Map.of(
                            "label", labelObj != null ? labelObj.toString() : valueObj.toString(),
                            "value", valueObj.toString()
                    ));
                }
            }
            return ResponseEntity.ok(options);
        } catch (Exception e) {
            return ResponseEntity.status(502).body(Map.of("error", "Enum source fetch failed: " + e.getMessage()));
        }
    }

    /**
     * 在预配置的 Linux 服务器上执行脚本命令。
     *
     * @param request 包含台账 id 与 command；需 {@code X-User-Id} 以解析当前用户下的服务器名称
     * @return 成功时 { "result": "..." }，失败时返回错误信息
     */
    @PostMapping("/linux-script")
    public ResponseEntity<Map<String, Object>> executeLinuxScript(
            @RequestHeader(value = "X-User-Id", required = false) String userId,
            @RequestBody LinuxScriptRequest request
    ) {
        if (userId == null || userId.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "X-User-Id header is required for linux-script"));
        }
        if (request.getId() == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "id is required"));
        }
        Optional<ServerLedger> ledgerOpt = serverLedgerService.getServerLedgerByUserIdAndId(userId, request.getId());
        if (ledgerOpt.isEmpty()) {
            gatewayOutboundAuditService.recordSsh(
                    userId,
                    "unknown",
                    0,
                    request.getCommand() != null ? request.getCommand() : "",
                    false,
                    "Unknown server id: " + request.getId(),
                    "skill.linux-script",
                    null,
                    request.getId()
            );
            return ResponseEntity.status(404).body(Map.of("error", "Unknown server id: " + request.getId()));
        }
        ServerLedger ledger = ledgerOpt.get();
        int defaultPort = ledger.getPort() != null && ledger.getPort() > 0 ? ledger.getPort() : 22;
        String defaultHost = ledger.getHost() != null ? ledger.getHost().trim() : "unknown";
        try {
            String output = linuxScriptExecutionService.executeFromLedger(ledger, request.getCommand());
            gatewayOutboundAuditService.recordSsh(
                    userId,
                    defaultHost,
                    defaultPort,
                    request.getCommand() != null ? request.getCommand() : "",
                    true,
                    null,
                    "skill.linux-script",
                    output,
                    ledger.getId()
            );
            return ResponseEntity.ok(Map.of("result", output));
        } catch (IllegalArgumentException e) {
            gatewayOutboundAuditService.recordSsh(
                    userId,
                    defaultHost,
                    defaultPort,
                    request.getCommand() != null ? request.getCommand() : "",
                    false,
                    e.getMessage(),
                    "skill.linux-script",
                    null,
                    ledger.getId()
            );
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (IOException e) {
            gatewayOutboundAuditService.recordSsh(
                    userId,
                    defaultHost,
                    defaultPort,
                    request.getCommand() != null ? request.getCommand() : "",
                    false,
                    e.getMessage(),
                    "skill.linux-script",
                    null,
                    ledger.getId()
            );
            return ResponseEntity.internalServerError().body(Map.of("error", "Linux script execution failed: " + e.getMessage()));
        }
    }

    /**
     * 执行计算运算。
     * 支持：时间戳转日期、日期差值、加减乘除、阶乘、平方、开方。
     *
     * @param request 包含 operation 和 operands 的请求体
     * @return 成功时 { "result": <value> }，失败时 { "error": "<message>" }
     */
    @PostMapping("/compute")
    public ResponseEntity<Map<String, Object>> compute(@RequestBody ComputeRequest request) {
        return ResponseEntity.ok(builtinToolExecutionService.compute(request));
    }

    /**
     * SSH 请求数据传输对象。
     */
    public static class SshRequest {
        private String host;
        private int port = 22;
        private String username;
        private String privateKey;
        private String command;
        // getters/setters omitted for brevity
        public String getHost() { return host; }
        public void setHost(String host) { this.host = host; }
        public int getPort() { return port; }
        public void setPort(int port) { this.port = port; }
        public String getUsername() { return username; }
        public void setUsername(String username) { this.username = username; }
        public String getPrivateKey() { return privateKey; }
        public void setPrivateKey(String privateKey) { this.privateKey = privateKey; }
        public String getCommand() { return command; }
        public void setCommand(String command) { this.command = command; }
    }

    /**
     * API 调用请求数据传输对象。
     */
    public static class ApiRequest {
        private String url;
        private String method;
        /**
         * Outgoing headers; values are usually strings. Arrays (e.g. {@code "Origin": ["https://a"]})
         * are accepted so OpenAPI-style or UI-exported skills deserialize; see {@code ApiProxyService}.
         */
        private Map<String, Object> headers;
        private Object body;
        private Integer timeoutSeconds;
        private Map<String, Object> asyncPoll;
        // getters/setters
        public String getUrl() { return url; }
        public void setUrl(String url) { this.url = url; }
        public String getMethod() { return method; }
        public void setMethod(String method) { this.method = method; }
        public Map<String, Object> getHeaders() { return headers; }
        public void setHeaders(Map<String, Object> headers) { this.headers = headers; }
        public Object getBody() { return body; }
        public void setBody(Object body) { this.body = body; }
        public Integer getTimeoutSeconds() { return timeoutSeconds; }
        public void setTimeoutSeconds(Integer timeoutSeconds) { this.timeoutSeconds = timeoutSeconds; }
        public Map<String, Object> getAsyncPoll() { return asyncPoll; }
        public void setAsyncPoll(Map<String, Object> asyncPoll) { this.asyncPoll = asyncPoll; }
    }

    /**
     * 计算请求数据传输对象。
     */
    public static class ComputeRequest {
        private String operation;
        private List<Object> operands;

        public String getOperation() { return operation; }
        public void setOperation(String operation) { this.operation = operation; }
        public List<Object> getOperands() { return operands; }
        public void setOperands(List<Object> operands) { this.operands = operands; }
    }
}
