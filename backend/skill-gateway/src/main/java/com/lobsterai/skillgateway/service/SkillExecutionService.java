package com.lobsterai.skillgateway.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lobsterai.skillgateway.audit.HttpClientAuditMode;
import com.lobsterai.skillgateway.config.DedupConfig;
import com.lobsterai.skillgateway.dto.FileToolResponse;
import com.lobsterai.skillgateway.entity.AsyncTask;
import com.lobsterai.skillgateway.entity.PythonSandbox;
import com.lobsterai.skillgateway.entity.ServerLedger;
import com.lobsterai.skillgateway.entity.Skill;
import com.lobsterai.skillgateway.util.JsonSchemaValidator;
import com.lobsterai.skillgateway.util.RequestSignatureUtil;
import com.lobsterai.skillgateway.util.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URLEncoder;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
public class SkillExecutionService {

    private static final Logger log = LoggerFactory.getLogger(SkillExecutionService.class);

    private final SkillService skillService;
    private final ApiProxyService apiProxyService;
    private final SSHExecutorService sshExecutorService;
    private final SecurityFilterService securityFilterService;
    private final ServerLedgerService serverLedgerService;
    private final LinuxScriptExecutionService linuxScriptExecutionService;
    private final GatewayOutboundAuditService gatewayOutboundAuditService;
    private final PendingConfirmationStore confirmationStore;
    private final AsyncTaskPollingService asyncTaskPollingService;
    private final AsyncTaskPollingScheduler asyncTaskPollingScheduler;
    private final PythonSandboxService pythonSandboxService;
    private final JsonSchemaValidator jsonSchemaValidator;
    private final ObjectMapper objectMapper;
    private final FileToolService fileToolService;

    public SkillExecutionService(
            SkillService skillService,
            ApiProxyService apiProxyService,
            SSHExecutorService sshExecutorService,
            SecurityFilterService securityFilterService,
            ServerLedgerService serverLedgerService,
            LinuxScriptExecutionService linuxScriptExecutionService,
            GatewayOutboundAuditService gatewayOutboundAuditService,
            PendingConfirmationStore confirmationStore,
            AsyncTaskPollingService asyncTaskPollingService,
            AsyncTaskPollingScheduler asyncTaskPollingScheduler,
            ObjectMapper objectMapper,
            FileToolService fileToolService,
            PythonSandboxService pythonSandboxService,
            JsonSchemaValidator jsonSchemaValidator
    ) {
        this.skillService = skillService;
        this.apiProxyService = apiProxyService;
        this.sshExecutorService = sshExecutorService;
        this.securityFilterService = securityFilterService;
        this.serverLedgerService = serverLedgerService;
        this.linuxScriptExecutionService = linuxScriptExecutionService;
        this.gatewayOutboundAuditService = gatewayOutboundAuditService;
        this.confirmationStore = confirmationStore;
        this.asyncTaskPollingService = asyncTaskPollingService;
        this.asyncTaskPollingScheduler = asyncTaskPollingScheduler;
        this.pythonSandboxService = pythonSandboxService;
        this.jsonSchemaValidator = jsonSchemaValidator;
        this.objectMapper = objectMapper;
        this.fileToolService = fileToolService;
    }

    public Object execute(ExecuteRequest request) throws Exception {
        Skill skill = skillService.getSkillByIdForUser(request.skillId, request.userId)
                .orElseThrow(() -> new IllegalArgumentException("Skill not found or disabled: " + request.skillId));

        if (!skill.isEnabled()) {
            throw new IllegalArgumentException("Skill is disabled: " + request.skillId);
        }

        Map<String, Object> config = parseConfiguration(skill.getConfiguration());

        if (skill.isRequiresConfirmation() && !request.isConfirmed()) {
            String requestId = confirmationStore.put(
                    skill.getId(),
                    skill.getName(),
                    request.parameters,
                    request.userId
            );
            Map<String, Object> confirmResponse = new LinkedHashMap<>();
            confirmResponse.put("status", "CONFIRMATION_REQUIRED");
            confirmResponse.put("requestId", requestId);
            confirmResponse.put("skillName", skill.getName());
            confirmResponse.put("skillId", skill.getId());
            confirmResponse.put("parameters", request.parameters);
            confirmResponse.put("expiresInSeconds", 300);
            return confirmResponse;
        }

        Object effectiveParameters = request.parameters;
        if (request.isConfirmed() && request.requestId != null) {
            PendingConfirmationStore.PendingConfirmation conf =
                    confirmationStore.getIfValid(request.requestId, request.userId);
            if (conf == null) {
                Map<String, Object> error = new LinkedHashMap<>();
                error.put("error", "Confirmation request not found or expired");
                return error;
            }
            if (request.adjustedParams != null) {
                effectiveParameters = mergeParameters(conf.parameters, request.adjustedParams);
            }
            confirmationStore.remove(request.requestId);

            // 二次确认的关键：把 confirmed=true 注入到 parameters，
            // 这样下层 handler（如 FileManageService.fileDelete 读 params.confirmed）才能感知。
            // 之前 effectiveParameters 没带 confirmed 标记，导致 file_delete/file_clear_all
            // 第二次仍走 "返回 requiresConfirmation" 分支，无法真正执行。
            if (effectiveParameters instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> paramMap = (Map<String, Object>) effectiveParameters;
                if (!paramMap.containsKey("confirmed")) {
                    paramMap.put("confirmed", Boolean.TRUE);
                }
            } else {
                Map<String, Object> wrapped = new LinkedHashMap<String, Object>();
                wrapped.put("confirmed", Boolean.TRUE);
                if (effectiveParameters != null) {
                    wrapped.put("_originalParams", effectiveParameters);
                }
                effectiveParameters = wrapped;
            }
        }

        effectiveParameters = mergeDefaults(effectiveParameters, config);

        @SuppressWarnings("unchecked")
        Map<String, Object> asyncPollConfig = (Map<String, Object>) config.get("asyncPoll");
        if (asyncPollConfig != null) {
            return executeApiSkillAsync(skill, config, effectiveParameters, request.userId, request.getSessionId(), request.parentToolId, request.parentSkillId);
        }

        String kind = (String) config.getOrDefault("kind", "api");
        switch (kind) {
            case "api":
                return executeApiSkill(config, effectiveParameters);
            case "ssh":
                return executeSshSkill(skill, config, effectiveParameters, request.userId);
            case "template":
                return executeTemplateSkill(config, effectiveParameters);
            case "file_tool":
                return executeFileToolSkill(config, effectiveParameters, request.userId, request.conversationId);
            case "python":
                return executePythonSkill(skill, config, effectiveParameters, request.userId);
            default:
                throw new IllegalArgumentException("Unsupported skill kind: " + kind);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> mergeDefaults(Object parameters, Map<String, Object> config) {
        Map<String, Object> paramContract = (Map<String, Object>) config.get("parameterContract");
        if (paramContract == null) {
            return asMap(parameters);
        }
        Map<String, Object> properties = (Map<String, Object>) paramContract.get("properties");
        if (properties == null) {
            return asMap(parameters);
        }

        Map<String, Object> merged = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : properties.entrySet()) {
            Map<String, Object> propDef = (Map<String, Object>) entry.getValue();
            if (propDef != null) {
                if (propDef.containsKey("default")) {
                    merged.put(entry.getKey(), propDef.get("default"));
                } else if (propDef.containsKey("const")) {
                    merged.put(entry.getKey(), propDef.get("const"));
                }
            }
        }

        Map<String, Object> inputMap = asMap(parameters);
        merged.putAll(inputMap);
        return merged;
    }

    @SuppressWarnings("unchecked")
    private Object executeApiSkill(Map<String, Object> config, Object parameters) throws Exception {
        String endpoint = (String) config.get("endpoint");
        if (endpoint == null || StringUtils.isBlank(endpoint)) {
            throw new IllegalArgumentException("API skill missing endpoint");
        }

        String method = (String) config.getOrDefault("method", "GET");
        Map<String, Object> requestHeaders = new LinkedHashMap<>();
        Map<String, Object> configHeaders = (Map<String, Object>) config.get("headers");
        if (configHeaders != null) {
            requestHeaders.putAll(configHeaders);
        }

        String binding = (String) config.getOrDefault("parameterBinding", "query");
        Map<String, Object> paramMap = asMap(parameters);
        Map<String, Object> queryParams = new LinkedHashMap<>();
        Object body = config.get("body");
        boolean useJsonBody = false;
        boolean useFormBody = false;

        String upperMethod = method.toUpperCase();
        if ("jsonBody".equals(binding) && isBodyMethod(upperMethod)) {
            useJsonBody = true;
        } else if ("formBody".equals(binding) && isBodyMethod(upperMethod)) {
            useFormBody = true;
        }

        if (useJsonBody || useFormBody) {
            Map<String, Object> bodyMap = new LinkedHashMap<>();
            Map<String, Object> inputQuery = (Map<String, Object>) paramMap.get("query");
            Map<String, Object> inputHeaders = (Map<String, Object>) paramMap.get("headers");
            Map<String, Object> inputBody = (Map<String, Object>) paramMap.get("body");

            for (Map.Entry<String, Object> entry : paramMap.entrySet()) {
                String key = entry.getKey();
                if ("query".equals(key) || "headers".equals(key) || "body".equals(key)) {
                    continue;
                }
                bodyMap.put(key, entry.getValue());
            }
            if (inputBody != null) {
                bodyMap.putAll(inputBody);
            }
            if (useFormBody) {
                for (Object value : bodyMap.values()) {
                    if (value instanceof Map || value instanceof List) {
                        throw new IllegalArgumentException("formBody requires flat scalar parameters");
                    }
                }
                if (!requestHeaders.containsKey("Content-Type")) {
                    requestHeaders.put("Content-Type", MediaType.APPLICATION_FORM_URLENCODED_VALUE);
                }
                body = encodeFormBody(bodyMap);
            } else {
                if (!requestHeaders.containsKey("Content-Type")) {
                    requestHeaders.put("Content-Type", MediaType.APPLICATION_JSON_VALUE);
                }
                body = bodyMap;
            }
            if (queryParams != null && inputQuery != null) {
                queryParams.putAll(inputQuery);
            }
            if (inputHeaders != null) {
                requestHeaders.putAll(inputHeaders);
            }
        } else {
            for (Map.Entry<String, Object> entry : paramMap.entrySet()) {
                String key = entry.getKey();
                if ("query".equals(key)) {
                    Map<String, Object> q = (Map<String, Object>) entry.getValue();
                    if (q != null) queryParams.putAll(q);
                } else if ("headers".equals(key)) {
                    Map<String, Object> h = (Map<String, Object>) entry.getValue();
                    if (h != null) requestHeaders.putAll(h);
                } else if ("body".equals(key)) {
                    body = entry.getValue();
                } else {
                    queryParams.put(key, entry.getValue());
                }
            }
        }

        String fullUrl = buildUrlWithQuery(endpoint, queryParams);
        return apiProxyService.callApi(fullUrl, upperMethod, requestHeaders, body, HttpClientAuditMode.SKILL_OUTBOUND);
    }

    @SuppressWarnings("unchecked")
    private Object executeSshSkill(Skill skill, Map<String, Object> config, Object parameters, String userId) throws IOException {
        String command = (String) config.get("command");
        if (command == null || StringUtils.isBlank(command)) {
            throw new IllegalArgumentException("SSH skill missing command");
        }
        if (!securityFilterService.isCommandSafe(command)) {
            gatewayOutboundAuditService.recordSsh(
                    userId, "unknown", 22, command,
                    false, "Command blocked by security policy",
                    "skill.execute", null, skill.getId()
            );
            Map<String, Object> error = new LinkedHashMap<>();
            error.put("error", "Command blocked by security policy");
            return error;
        }

        Map<String, Object> paramMap = asMap(parameters);
        String hostOrName = (String) paramMap.getOrDefault("host",
                paramMap.getOrDefault("name", paramMap.getOrDefault("serverName", null)));

        // Support legacy two-step flow: server_lookup returns {id, name}, LLM passes id
        if (hostOrName == null || StringUtils.isBlank(hostOrName)) {
            Object idObj = paramMap.get("id");
            if (idObj instanceof Number) {
                if (userId == null || StringUtils.isBlank(userId)) {
                    throw new IllegalArgumentException("SSH skill requires X-User-Id header");
                }
                Optional<ServerLedger> ledgerOpt = serverLedgerService.getServerLedgerByUserIdAndId(userId, ((Number) idObj).longValue());
                if (ledgerOpt.isPresent()) {
                    hostOrName = ledgerOpt.get().getName();
                }
            }
        }

        if (hostOrName == null || StringUtils.isBlank(hostOrName)) {
            throw new IllegalArgumentException("SSH skill requires host or server name parameter");
        }
        if (userId == null || StringUtils.isBlank(userId)) {
            throw new IllegalArgumentException("SSH skill requires X-User-Id header");
        }

        Optional<ServerLedger> ledgerOpt = serverLedgerService.getServerLedgerByName(userId, hostOrName.trim());
        if (!ledgerOpt.isPresent()) {
            gatewayOutboundAuditService.recordSsh(
                    userId, hostOrName.trim(), 22, command,
                    false, "Server not found in user ledger: " + hostOrName,
                    "skill.execute", null, skill.getId()
            );
            Map<String, Object> error = new LinkedHashMap<>();
            error.put("error", "Server not found in user ledger: " + hostOrName);
            return error;
        }

        ServerLedger ledger = ledgerOpt.get();
        int port = ledger.getPort() != null && ledger.getPort() > 0 ? ledger.getPort() : 22;
        String host = ledger.getHost() != null && !StringUtils.isBlank(ledger.getHost())
                ? ledger.getHost().trim() : hostOrName.trim();

        try {
            String output = linuxScriptExecutionService.executeFromLedger(ledger, command);
            gatewayOutboundAuditService.recordSsh(
                    userId, host, port, command,
                    true, null, "skill.execute", output, ledger.getId()
            );
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("result", output);
            return result;
        } catch (IllegalArgumentException e) {
            gatewayOutboundAuditService.recordSsh(
                    userId, host, port, command,
                    false, e.getMessage(), "skill.execute", null, ledger.getId()
            );
            Map<String, Object> error = new LinkedHashMap<>();
            error.put("error", e.getMessage());
            return error;
        } catch (IOException e) {
            gatewayOutboundAuditService.recordSsh(
                    userId, host, port, command,
                    false, e.getMessage(), "skill.execute", null, ledger.getId()
            );
            Map<String, Object> error = new LinkedHashMap<>();
            error.put("error", "SSH execution failed: " + e.getMessage());
            return error;
        }
    }

    private Object executeTemplateSkill(Map<String, Object> config, Object parameters) {
        String prompt = (String) config.get("prompt");
        if (prompt == null || StringUtils.isBlank(prompt)) {
            throw new IllegalArgumentException("Template skill missing prompt");
        }

        String rendered = renderTemplate(prompt, asMap(parameters));

        java.util.List<String> unfilled = findUnfilledPlaceholders(rendered);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("kind", "template");
        result.put("rendered", rendered);
        if (!unfilled.isEmpty()) {
            result.put("warning", "Unfilled placeholders: " + String.join(", ", unfilled)
                + ". Ask the user to provide these values and call again.");
        }
        result.put("instruction",
            "Use the rendered content above as your system prompt to generate a response. "
            + "Do NOT call this tool again for the same request.");
        return result;
    }

    /**
     * Python Skill: 把 LLM 透传参数（payload 整体）作为 script_args + Skill config.code 拼装为 body 转发到 python_sandbox.endpoint_url。
     * 入参校验：按 sandbox.service_params (JSON Schema) 校验 script_args 字段。
     * 响应透传整 body（不做 JsonPath 提取）。详见 docs/python-execution-skill-design.md §5.2 / §5.3。
     */
    @SuppressWarnings("unchecked")
    private Object executePythonSkill(Skill skill, Map<String, Object> config, Object parameters, String userId) throws Exception {
        String sandboxName = (String) config.get("sandboxName");
        if (StringUtils.isBlank(sandboxName)) {
            throw new IllegalArgumentException("Python skill missing sandboxName");
        }
        Object code = config.get("code");
        if (code == null || (code instanceof String && StringUtils.isBlank((String) code))) {
            throw new IllegalArgumentException("Python skill missing code");
        }
        PythonSandbox sandbox = pythonSandboxService.getByNameOrThrow(sandboxName.trim());
        if (sandbox.getEnabled() == null || sandbox.getEnabled() != 1) {
            throw new IllegalArgumentException("Python sandbox disabled: " + sandbox.getName());
        }
        // 1. 解析 service_params 为 schema；非 JSON 对象抛 IllegalArgumentException
        Map<String, Object> schema;
        try {
            schema = pythonSandboxService.parseServiceParams(sandbox);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid service_params JSON for sandbox: "
                    + sandbox.getName() + " — " + e.getMessage(), e);
        }
        // 2. 把 LLM 透传的 payload 整体作为 script_args；按 service_params schema 校验 script_args
        //    （service_params 描述的是 script_args 这个值的结构，不是 LLM 整个入参）
        Map<String, Object> scriptArgs = asMap(parameters);
        if (scriptArgs == null) {
            scriptArgs = new LinkedHashMap<>();
        }
        jsonSchemaValidator.validate(objectMapper.writeValueAsString(schema), scriptArgs);

        // 3. 拼装出站请求
        String method = sandbox.getHttpMethod() == null ? "POST" : sandbox.getHttpMethod();
        String url = sandbox.getEndpointUrl();
        Map<String, Object> requestHeaders = new LinkedHashMap<>();
        requestHeaders.put("Content-Type", MediaType.APPLICATION_JSON_VALUE);

        // 4. 拼装 body：code 来自 Skill config（用户写死的脚本）；script_args 来自 LLM 透传整体。
        //    code / script_args 字段名硬编码（沙箱协议约定）。
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", code);
        body.put("script_args", scriptArgs);

        // 诊断日志：记录 code 长度 + sha256 前 8 位，方便核对「出站 code 是不是用户写的 code」
        if (code instanceof String) {
            String codeStr = (String) code;
            String digest = Integer.toHexString(codeStr.hashCode());
            log.info("PythonSkill outbound: code length={}, sha256[0:8]={}, sandbox={}, skill={}",
                    codeStr.length(), digest, sandbox.getName(), skill.getId());
        }

        return apiProxyService.callApi(url, method.toUpperCase(), requestHeaders, body,
                HttpClientAuditMode.SKILL_OUTBOUND);
    }

    private String renderTemplate(String template, Map<String, Object> params) {
        String result = template;
        for (Map.Entry<String, Object> entry : params.entrySet()) {
            result = result.replace("{{" + entry.getKey() + "}}", String.valueOf(entry.getValue()));
        }
        return result;
    }

    private java.util.List<String> findUnfilledPlaceholders(String rendered) {
        java.util.List<String> unfilled = new java.util.ArrayList<>();
        java.util.regex.Pattern p = java.util.regex.Pattern.compile("\\{\\{([^{}]+)\\}\\}");
        java.util.regex.Matcher m = p.matcher(rendered);
        while (m.find()) {
            unfilled.add(m.group(1));
        }
        return unfilled;
    }

    private Map<String, Object> parseConfiguration(String configuration) {
        if (configuration == null || StringUtils.isBlank(configuration)) {
            return Collections.emptyMap();
        }
        try {
            return objectMapper.readValue(configuration, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            return Collections.emptyMap();
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> asMap(Object parameters) {
        if (parameters instanceof Map) {
            return new LinkedHashMap<>((Map<String, Object>) parameters);
        }
        return new LinkedHashMap<>();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> mergeParameters(Object original, Object adjusted) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (original instanceof Map) {
            result.putAll((Map<String, Object>) original);
        }
        if (adjusted instanceof Map) {
            result.putAll((Map<String, Object>) adjusted);
        }
        return result;
    }

    private boolean isBodyMethod(String method) {
        return "POST".equals(method) || "PUT".equals(method) || "PATCH".equals(method) || "DELETE".equals(method);
    }

    private String buildUrlWithQuery(String endpoint, Map<String, Object> queryParams) {
        if (queryParams.isEmpty()) {
            return endpoint;
        }
        StringBuilder sb = new StringBuilder(endpoint);
        boolean first = !endpoint.contains("?");
        for (Map.Entry<String, Object> entry : queryParams.entrySet()) {
            if (entry.getValue() == null) continue;
            sb.append(first ? "?" : "&");
            first = false;
            try {
                // 单次 URL 编码（RFC 3986 percent-encoding）。
                // URLEncoder.encode(String, String) 自 JDK 1.4 就存在，不是 JDK 10+。
                // 修复前这里误加了一段冗余的 URLEncoder.encode(key) 单参数调用，导致
                // 拼出来的 URL 是 "key=valkey=val" 双重编码，后端解析失败。
                sb.append(URLEncoder.encode(entry.getKey(), "UTF-8"));
                sb.append("=");
                sb.append(URLEncoder.encode(String.valueOf(entry.getValue()), "UTF-8"));
            } catch (java.io.UnsupportedEncodingException e) {
                throw new RuntimeException(e);
            }
        }
        return sb.toString();
    }

    private MultiValueMap<String, String> encodeFormBody(Map<String, Object> bodyMap) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        for (Map.Entry<String, Object> entry : bodyMap.entrySet()) {
            if (entry.getValue() == null) continue;
            form.add(entry.getKey(), String.valueOf(entry.getValue()));
        }
        return form;
    }

    @SuppressWarnings("unchecked")
    private Object executeApiSkillAsync(Skill skill, Map<String, Object> config, Object parameters, String userId, String sessionId, String parentToolId, Long parentSkillId) throws Exception {
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
            if (singleCallReadTimeoutSeconds == null || singleCallReadTimeoutSeconds < 10) {
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
            task.setParentToolId(parentToolId);
            task.setParentSkillId(parentSkillId);
            task.setPollStrategy("SINGLE_CALL");
            task.setPollEndpoint(endpoint);
            task.setPollMethod(method);
            task.setRequestBody(requestBody);
            task.setPollHeaders(pollHeadersJson);
            task.setSingleCallReadTimeoutSeconds(singleCallReadTimeoutSeconds);
            task.setMaxWaitSeconds(singleCallReadTimeoutSeconds);  // SINGLE_CALL 的超时也用作 maxWaitSeconds
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
        if (externalTaskId == null || StringUtils.isBlank(externalTaskId)) {
            Map<String, Object> error = new LinkedHashMap<>();
            error.put("status", "FAILED");
            error.put("error", "Failed to extract task id from initial response");
            error.put("idJsonPath", idJsonPath);
            error.put("initialResponse", initialResponseStr.substring(0, Math.min(500, initialResponseStr.length())));
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
        task.setParentToolId(parentToolId);
        task.setParentSkillId(parentSkillId);
        task.setExternalTaskId(externalTaskId);
        task.setPollEndpoint(pollEndpoint);
        task.setPollMethod(pollMethod);
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

    /**
     * 分发 file_tool 类技能到 {@link FileToolService} 统一调度。
     * <p>
     * 支持两种 configuration 模式：
     * </p>
     * <ol>
     *   <li><b>细粒度模式（legacy）</b>：{@code config.toolName} = "word_read" / "txt_distinct_lines" 等具体工具名
     *       —— 老的 file_* / word_* / txt_* 工具走这条路径</li>
     *   <li><b>family 整合模式（新）</b>：{@code config.family} = "word" / "txt" 等，
     *       从 {@code parameters.action} 拼出内部 toolName = "{family}_{action}"（如 word_read），
     *       委派给现有 {@link FileToolService} 调度 —— 内部 handler 注册表一行不动</li>
     * </ol>
     * <p>
     * family 模式的好处是：对外（LLM）只暴露 1 个工具（如 word_ops），
     * 对内（gateway 调度）仍复用已注册的 word_read/write/... 等细粒度 handler。
     * </p>
     */
    @SuppressWarnings("unchecked")
    private Object executeFileToolSkill(Map<String, Object> config, Object parameters, String userId,
                                        String conversationId) {
        String toolName = resolveFileToolName(config, parameters);
        Map<String, Object> params = parameters instanceof Map
                ? (Map<String, Object>) parameters
                : new LinkedHashMap<String, Object>();
        // open spec: conversation-file-isolation — 把 conversationId 传入 4 参 overload，
        // 让 FileToolService 解析 enabled_files 并按会话过滤
        FileToolResponse response = fileToolService.execute(userId, toolName, params, conversationId);

        // file_tool 内部 file_delete / file_clear_all 返回 { requiresConfirmation: true, ... }
        // 必须把这个内部信号转成顶层 CONFIRMATION_REQUIRED 协议，
        // 否则 agent-core 看到 success=true 就当成操作已完成
        if (response.isSuccess() && response.getOutput() instanceof Map) {
            Map<String, Object> outputMap = (Map<String, Object>) response.getOutput();
            Object rcFlag = outputMap.get("requiresConfirmation");
            if (Boolean.TRUE.equals(rcFlag)) {
                String requestId = confirmationStore.put(
                        null,                    // skillId 留空（file_tool 不绑定 skills.id）
                        toolName,                // 用 toolName 当 skillName
                        parameters,
                        userId
                );
                Map<String, Object> confirmResponse = new LinkedHashMap<String, Object>();
                confirmResponse.put("status", "CONFIRMATION_REQUIRED");
                confirmResponse.put("requestId", requestId);
                confirmResponse.put("skillName", toolName);
                confirmResponse.put("skillId", null);
                confirmResponse.put("parameters", parameters);
                confirmResponse.put("expiresInSeconds", 300);
                return confirmResponse;
            }
        }

        Map<String, Object> result = new LinkedHashMap<String, Object>();
        if (response.isSuccess()) {
            result.put("success", true);
            result.put("output", response.getOutput());
        } else {
            result.put("success", false);
            result.put("message", response.getMessage());
        }
        if (response.getFileRef() != null) {
            result.put("fileRef", response.getFileRef());
        }
        return result;
    }

    /**
     * 解析 file_tool 实际要调用的内部 toolName。
     * <p>
     * 优先级：先看 {@code config.family}（整合模式），再看 {@code config.toolName}（legacy 模式）。
     * </p>
     *
     * @throws IllegalArgumentException 配置错误或缺 action 时
     */
    private String resolveFileToolName(Map<String, Object> config, Object parameters) {
        // 模式 1：family 整合模式（新）
        Object familyObj = config.get("family");
        if (familyObj != null) {
            String family = String.valueOf(familyObj).trim();
            if (family.isEmpty()) {
                throw new IllegalArgumentException("file_tool family-mode configuration has empty 'family'");
            }
            Map<String, Object> paramMap = parameters instanceof Map
                    ? (Map<String, Object>) parameters : new LinkedHashMap<String, Object>();
            Object actionObj = paramMap.get("action");
            if (actionObj == null) {
                throw new IllegalArgumentException(
                        "file_tool family '" + family + "' requires 'action' parameter " +
                        "(e.g. action=read / write / extract_content / search_keyword / replace_text / template_fill)");
            }
            String action = String.valueOf(actionObj).trim();
            if (action.isEmpty()) {
                throw new IllegalArgumentException("file_tool family '" + family + "' has empty 'action'");
            }
            return family + "_" + action;   // 例："word" + "_" + "read" = "word_read"
        }

        // 模式 2：legacy 细粒度模式
        Object toolNameObj = config.get("toolName");
        if (toolNameObj == null) {
            throw new IllegalArgumentException(
                    "file_tool configuration must contain either 'family' (integration mode) or 'toolName' (legacy mode)");
        }
        String toolName = String.valueOf(toolNameObj).trim();
        if (toolName.isEmpty()) {
            throw new IllegalArgumentException("file_tool legacy-mode has empty 'toolName'");
        }
        return toolName;
    }

    public static class ExecuteRequest {
        public Long skillId;
        public Object parameters;
        public boolean confirmed;
        public String requestId;
        public String userId;
        public Object adjustedParams;
        public String sessionId;
        /** conversation-file-isolation: 当前对话 ID（用于 file_tool 解析 enabled_files） */
        public String conversationId;
        /** bxdcbot-multi-turn-async: 父 Bxdcbot run_id (NULL=普通 async) */
        public String parentToolId;
        /** bxdcbot-multi-turn-async: 父 Bxdcbot skill_id (NULL=非 Bxdcbot 调起) */
        public Long parentSkillId;
        /** conversation-file-isolation: 当前会话 ID（用于 file_tool 按 enabled_files 过滤） */
        public String conversationId;

        public boolean isConfirmed() {
            return confirmed;
        }

        public String getSessionId() {
            return sessionId;
        }

        public String getConversationId() {
            return conversationId;
        }
    }
}

