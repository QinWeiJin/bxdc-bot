package com.lobsterai.skillgateway.controller;

import com.lobsterai.skillgateway.entity.PythonSandbox;
import com.lobsterai.skillgateway.entity.SystemSkill;
import com.lobsterai.skillgateway.service.PythonSandboxService;
import com.lobsterai.skillgateway.service.SystemSkillService;
import com.lobsterai.skillgateway.util.StringUtils;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Collections;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 系统 Built-in Skill（独立于 {@code /api/skills} 扩展 CRUD）。
 * Agent 在 gateway 模式下通过本控制器发现与执行内置工具。
 */
@RestController
@RequestMapping("/api/system-skills")
@CrossOrigin(origins = "*")
public class SystemSkillController {

    private final SystemSkillService systemSkillService;
    private final PythonSandboxService pythonSandboxService;

    public SystemSkillController(SystemSkillService systemSkillService,
                                 PythonSandboxService pythonSandboxService) {
        this.systemSkillService = systemSkillService;
        this.pythonSandboxService = pythonSandboxService;
    }

    /**
     * 返回所有可用的 Skill 执行类型及其配置表单 Schema，供前端动态渲染。
     */
    @GetMapping("/execution-types")
    public List<Map<String, Object>> listExecutionTypes() {
        List<Map<String, Object>> types = new java.util.ArrayList<>();

        // API Skill
        Map<String, Object> apiType = new LinkedHashMap<>();
        apiType.put("type", "api");
        apiType.put("label", "API 调用");
        apiType.put("configSchema", buildApiConfigSchema());
        types.add(apiType);

        // SSH Skill
        Map<String, Object> sshType = new LinkedHashMap<>();
        sshType.put("type", "ssh");
        sshType.put("label", "SSH 执行");
        sshType.put("configSchema", buildSshConfigSchema());
        types.add(sshType);

        // Template Skill
        Map<String, Object> templateType = new LinkedHashMap<>();
        templateType.put("type", "template");
        templateType.put("label", "提示词模板");
        templateType.put("configSchema", buildTemplateConfigSchema());
        types.add(templateType);

        // Python Skill（动态从 python_sandbox 表加载；每行 enabled=true 一项）
        for (PythonSandbox sandbox : pythonSandboxService.listEnabled()) {
            Map<String, Object> pythonType = new LinkedHashMap<>();
            pythonType.put("type", "python");
            pythonType.put("label", sandbox.getName()
                    + (StringUtils.isBlank(sandbox.getDescription()) ? "" : ": " + sandbox.getDescription()));
            pythonType.put("configSchema", buildPythonConfigSchema());
            types.add(pythonType);
        }

        return types;
    }

    /**
     * Python Skill 配置 schema —— 与 api/ssh 同形，靠 ConfigFormRenderer 动态渲染。
     * sandboxName 用 select 渲染（候选由前端另外拉 /api/python-sandbox?enabled=true）。
     * code 是用户写死的 Python 源码，调用时由 Gateway 注入到出站 body（覆盖 LLM 误传）。
     * operation 决定 LLM 工具名后缀。
     */
    private Map<String, Object> buildPythonConfigSchema() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        Map<String, Object> props = new LinkedHashMap<>();

        Map<String, Object> sandboxName = new LinkedHashMap<>();
        sandboxName.put("type", "string");
        sandboxName.put("label", "Python 沙箱");
        sandboxName.put("required", true);
        sandboxName.put("ui", "select");
        sandboxName.put("aiHint", "引用 python_sandbox 表里的外部沙箱服务名（由 admin 配置）");
        props.put("sandboxName", sandboxName);

        Map<String, Object> code = new LinkedHashMap<>();
        code.put("type", "string");
        code.put("label", "Python 脚本");
        code.put("required", true);
        code.put("ui", "textarea");
        code.put("placeholder", "print('hello')\nimport sys\nprint(sys.version)");
        code.put("aiHint", "用户写死的 Python 源码。调用时由 Gateway 注入到出站 body 的 code 字段（覆盖 LLM 误传），由沙箱服务执行");
        props.put("code", code);

        Map<String, Object> operation = new LinkedHashMap<>();
        operation.put("type", "string");
        operation.put("label", "操作标识");
        operation.put("required", true);
        operation.put("ui", "input");
        operation.put("placeholder", "例如：run-python");
        operation.put("aiHint", "唯一标识该 Skill 操作的 key（LLM 工具名后缀）");
        props.put("operation", operation);

        Map<String, Object> iface = new LinkedHashMap<>();
        iface.put("type", "string");
        iface.put("label", "接口功能描述");
        iface.put("ui", "textarea");
        iface.put("aiHint", "向 LLM 解释这个 Python Skill 的用途和入参约束");
        props.put("interfaceDescription", iface);

        schema.put("properties", props);
        return schema;
    }

    private Map<String, Object> buildApiConfigSchema() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        Map<String, Object> props = new LinkedHashMap<>();

        Map<String, Object> preset = new LinkedHashMap<>();
        preset.put("type", "string");
        preset.put("label", "预配置模板");
        preset.put("ui", "select");
        preset.put("enum", java.util.Arrays.asList("none", "current-time"));
        preset.put("default", "none");
        preset.put("aiHint", "通用接口 或 当前时间等预置模板");
        props.put("preset", preset);

        Map<String, Object> operation = new LinkedHashMap<>();
        operation.put("type", "string");
        operation.put("label", "操作标识");
        operation.put("required", true);
        operation.put("ui", "input");
        operation.put("placeholder", "例如：juhe-joke-list");
        operation.put("aiHint", "唯一标识该 Skill 操作的 key");
        props.put("operation", operation);

        Map<String, Object> method = new LinkedHashMap<>();
        method.put("type", "string");
        method.put("label", "请求方法");
        method.put("required", true);
        method.put("ui", "select");
        method.put("default", "GET");
        method.put("enum", java.util.Arrays.asList("GET", "POST", "PUT", "DELETE", "PATCH"));
        props.put("method", method);

        Map<String, Object> endpoint = new LinkedHashMap<>();
        endpoint.put("type", "string");
        endpoint.put("label", "请求地址");
        endpoint.put("required", true);
        endpoint.put("ui", "input");
        endpoint.put("placeholder", "https://api.example.com/path");
        endpoint.put("aiHint", "完整的 API 地址，不含 query string");
        props.put("endpoint", endpoint);

        Map<String, Object> binding = new LinkedHashMap<>();
        binding.put("type", "string");
        binding.put("label", "参数绑定方式");
        binding.put("ui", "select");
        binding.put("enum", java.util.Arrays.asList("query", "jsonBody", "formBody"));
        binding.put("default", "query");
        binding.put("aiHint", "query=URL参数, jsonBody=JSON请求体, formBody=表单编码");
        props.put("parameterBinding", binding);

        Map<String, Object> timestampField = new LinkedHashMap<>();
        timestampField.put("type", "string");
        timestampField.put("label", "响应时间戳字段");
        timestampField.put("ui", "input");
        timestampField.put("placeholder", "例如：sysTime2");
        timestampField.put("aiHint", "响应中表示服务器时间的字段名（可选）");
        props.put("responseTimestampField", timestampField);

        Map<String, Object> timeout = new LinkedHashMap<>();
        timeout.put("type", "number");
        timeout.put("label", "超时时间（秒）");
        timeout.put("ui", "number");
        timeout.put("default", 30);
        timeout.put("minimum", 1);
        timeout.put("maximum", 3600);
        props.put("timeoutSeconds", timeout);

        Map<String, Object> headers = new LinkedHashMap<>();
        headers.put("type", "object");
        headers.put("label", "请求头");
        headers.put("ui", "jsonEditor");
        Map<String, Object> headersAiOpt = new LinkedHashMap<>();
        headersAiOpt.put("fieldId", "api_headers");
        headers.put("aiOptimize", headersAiOpt);
        headers.put("aiHint", "以 JSON 对象格式填写，如 {\"Authorization\": \"Bearer xxx\"}");
        props.put("headers", headers);

        Map<String, Object> query = new LinkedHashMap<>();
        query.put("type", "object");
        query.put("label", "Query 参数");
        query.put("ui", "jsonEditor");
        Map<String, Object> queryAiOpt = new LinkedHashMap<>();
        queryAiOpt.put("fieldId", "api_query");
        query.put("aiOptimize", queryAiOpt);
        query.put("aiHint", "以 JSON 对象格式填写，如 {\"page\": 1, \"pagesize\": 10}");
        props.put("query", query);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", "object");
        body.put("label", "Body 参数");
        body.put("ui", "jsonEditor");
        Map<String, Object> bodyAiOpt = new LinkedHashMap<>();
        bodyAiOpt.put("fieldId", "api_body");
        body.put("aiOptimize", bodyAiOpt);
        body.put("aiHint", "以 JSON 对象格式填写请求体内容");
        props.put("body", body);

        Map<String, Object> iface = new LinkedHashMap<>();
        iface.put("type", "string");
        iface.put("label", "接口功能描述");
        iface.put("ui", "textarea");
        iface.put("aiHint", "向 LLM 解释这个 API 的用途和使用注意事项");
        props.put("interfaceDescription", iface);

        Map<String, Object> pc = new LinkedHashMap<>();
        pc.put("type", "object");
        pc.put("label", "参数格式契约");
        pc.put("ui", "jsonEditor");
        Map<String, Object> pcAiOpt = new LinkedHashMap<>();
        pcAiOpt.put("fieldId", "api_parameter_contract");
        pc.put("aiOptimize", pcAiOpt);
        props.put("parameterContract", pc);

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

        // asyncPollEnabled: 启用异步轮询开关
        Map<String, Object> asyncPollEnabled = new LinkedHashMap<>();
        asyncPollEnabled.put("type", "boolean");
        asyncPollEnabled.put("label", "启用异步轮询");
        asyncPollEnabled.put("ui", "checkbox");
        asyncPollEnabled.put("default", false);
        props.put("asyncPollEnabled", asyncPollEnabled);

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

        schema.put("properties", props);
        return schema;
    }

    private Map<String, Object> buildSshConfigSchema() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        Map<String, Object> props = new LinkedHashMap<>();

        Map<String, Object> preset = new LinkedHashMap<>();
        preset.put("type", "string");
        preset.put("label", "预配置模板");
        preset.put("ui", "input");
        preset.put("default", "服务器状态巡检");
        preset.put("readonly", true);
        preset.put("aiHint", "当前固定为服务器状态巡检模板");
        props.put("preset", preset);

        Map<String, Object> operation = new LinkedHashMap<>();
        operation.put("type", "string");
        operation.put("label", "操作标识");
        operation.put("required", true);
        operation.put("ui", "input");
        operation.put("placeholder", "例如：server-resource-status");
        operation.put("aiHint", "唯一标识该 SSH Skill 操作的 key");
        props.put("operation", operation);

        Map<String, Object> lookup = new LinkedHashMap<>();
        lookup.put("type", "string");
        lookup.put("label", "服务器查找器");
        lookup.put("required", true);
        lookup.put("ui", "input");
        lookup.put("placeholder", "例如：server_lookup");
        lookup.put("default", "server_lookup");
        lookup.put("aiHint", "用于查找目标服务器的工具名称");
        props.put("lookup", lookup);

        Map<String, Object> executor = new LinkedHashMap<>();
        executor.put("type", "string");
        executor.put("label", "执行器");
        executor.put("required", true);
        executor.put("ui", "input");
        executor.put("placeholder", "例如：linux_script_executor");
        executor.put("default", "linux_script_executor");
        executor.put("aiHint", "在服务器上执行命令的工具名称");
        props.put("executor", executor);

        Map<String, Object> command = new LinkedHashMap<>();
        command.put("type", "string");
        command.put("label", "Shell 命令");
        command.put("required", true);
        command.put("ui", "textarea");
        command.put("aiHint", "在目标服务器上执行的 Shell 命令，会经过安全策略过滤");
        props.put("command", command);

        Map<String, Object> iface = new LinkedHashMap<>();
        iface.put("type", "string");
        iface.put("label", "接口功能描述");
        iface.put("ui", "textarea");
        iface.put("aiHint", "向 LLM 解释这个 SSH Skill 的用途（两步流程：server_lookup → 执行命令）");
        props.put("interfaceDescription", iface);

        schema.put("properties", props);
        return schema;
    }

    private Map<String, Object> buildTemplateConfigSchema() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        Map<String, Object> props = new LinkedHashMap<>();

        Map<String, Object> prompt = new LinkedHashMap<>();
        prompt.put("type", "string");
        prompt.put("label", "提示词模板");
        prompt.put("required", true);
        prompt.put("ui", "textarea");
        prompt.put("placeholder", "请输入提示词模板，使用 {{占位符}} 标记动态内容");
        prompt.put("aiHint", "使用 {{占位符}} 标记需要用户输入的动态内容。Gateway 执行时自动替换。");
        Map<String, Object> promptAiOpt = new LinkedHashMap<>();
        promptAiOpt.put("fieldId", "template_prompt");
        prompt.put("aiOptimize", promptAiOpt);
        props.put("prompt", prompt);

        schema.put("properties", props);
        return schema;
    }

    /**
     * 面向 Agent 的内置 Skill 列表（来源：{@code system_skills} 表）。
     */
    @GetMapping("/agent")
    public List<Map<String, Object>> listForAgent() {
        return systemSkillService.listAgentSkills().stream()
                .map(this::toAgentDto)
                .collect(Collectors.toList());
    }

    private Map<String, Object> toAgentDto(SystemSkill s) {
        Map<String, Object> m = new HashMap<>();
        m.put("id", s.getId());
        m.put("toolName", s.getToolName());
        m.put("description", s.getDescription());
        m.put("kind", s.getKind());
        m.put("source", "built-in");
        m.put("enabled", s.isEnabled());
        return m;
    }

    public static class ExecuteBody {
        private String toolName;
        private Map<String, Object> arguments;

        public String getToolName() {
            return toolName;
        }

        public void setToolName(String toolName) {
            this.toolName = toolName;
        }

        public Map<String, Object> getArguments() {
            return arguments;
        }

        public void setArguments(Map<String, Object> arguments) {
            this.arguments = arguments;
        }
    }

    @PostMapping("/execute")
    public ResponseEntity<?> execute(
            @RequestHeader(value = "X-User-Id", required = false) String userId,
            @RequestBody ExecuteBody body
    ) {
        if (body == null || body.getToolName() == null || StringUtils.isBlank(body.getToolName())) {
            return ResponseEntity.badRequest().body(Collections.singletonMap("error", "toolName is required"));
        }
        try {
            Object result = systemSkillService.execute(body.getToolName(), body.getArguments(), userId);
            return ResponseEntity.ok(result);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Collections.singletonMap("error", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Collections.singletonMap("error", e.getMessage()));
        }
    }
}
