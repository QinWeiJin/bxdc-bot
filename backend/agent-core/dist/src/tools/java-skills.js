"use strict";
var __importDefault = (this && this.__importDefault) || function (mod) {
    return (mod && mod.__esModule) ? mod : { "default": mod };
};
Object.defineProperty(exports, "__esModule", { value: true });
exports.JavaApiTool = exports.JavaServerLookupTool = exports.JavaComputeTool = exports.apiCallerToolInputSchema = exports.computeToolInputSchema = void 0;
exports.getAgentBuiltinSkillDispatch = getAgentBuiltinSkillDispatch;
exports.formatToolError = formatToolError;
exports.readPreset = readPreset;
exports.describeGatewayExtendedTool = describeGatewayExtendedTool;
exports.normalizeParameterBindingValue = normalizeParameterBindingValue;
exports.normalizeExtendedConfig = normalizeExtendedConfig;
exports.parseSkillConfig = parseSkillConfig;
exports.normalizeParameterContractRequired = normalizeParameterContractRequired;
exports.normalizeGeneratedOperation = normalizeGeneratedOperation;
exports.sanitizeConfigForDisplay = sanitizeConfigForDisplay;
exports.gatewaySkillMutationHeaders = gatewaySkillMutationHeaders;
exports.gatewaySkillReadHeaders = gatewaySkillReadHeaders;
exports.loadGatewayExtendedTools = loadGatewayExtendedTools;
exports.buildConfirmedToolInputString = buildConfirmedToolInputString;
exports.buildConfirmedToolArgs = buildConfirmedToolArgs;
exports.invokeExtendedSkillWithConfirmed = invokeExtendedSkillWithConfirmed;
const messages_1 = require("@langchain/core/messages");
const langgraph_1 = require("@langchain/langgraph");
const tools_1 = require("@langchain/core/tools");
const zod_1 = require("zod");
const axios_1 = __importDefault(require("axios"));
const pinyin_pro_1 = require("pinyin-pro");
const openclaw_executor_1 = require("./openclaw-executor");
function getAgentBuiltinSkillDispatch() {
    const v = (process.env.AGENT_BUILTIN_SKILL_DISPATCH ?? "legacy").trim().toLowerCase();
    return v === "gateway" ? "gateway" : "legacy";
}
const COMPUTE_OPERATIONS = [
    "add",
    "subtract",
    "multiply",
    "divide",
    "factorial",
    "square",
    "sqrt",
    "timestamp_to_date",
    "date_diff_days",
];
exports.computeToolInputSchema = zod_1.z.object({
    operation: zod_1.z
        .enum(COMPUTE_OPERATIONS)
        .describe("add|subtract|multiply|divide: two numbers in operands. factorial|square|sqrt: one number. timestamp_to_date: one Unix timestamp (seconds or ms). date_diff_days: two calendar dates as YYYY-MM-DD strings."),
    operands: zod_1.z
        .array(zod_1.z.union([zod_1.z.number(), zod_1.z.string()]))
        .min(1)
        .describe("add: [3,5]. subtract|multiply|divide: [a,b]. factorial|square|sqrt: [n]. timestamp_to_date: [unixTs]. date_diff_days: [\"2026-03-08\",\"2026-03-12\"]."),
});
const serverLookupToolInputSchema = zod_1.z.object({
    serverName: zod_1.z
        .string()
        .min(1)
        .describe("User-visible server name to search; returns up to 5 candidate serverId values (no credentials)."),
});
exports.apiCallerToolInputSchema = zod_1.z.object({
    url: zod_1.z.string().url().describe("Full target URL"),
    method: zod_1.z
        .enum(["GET", "POST", "PUT", "DELETE", "PATCH"])
        .default("GET")
        .describe("HTTP method"),
    headers: zod_1.z
        .record(zod_1.z.string(), zod_1.z.string())
        .optional()
        .describe("Additional headers (Authorization, Content-Type, etc.)"),
    body: zod_1.z
        .any()
        .optional()
        .describe("Request body (object, string, or null)"),
});
const tool_trace_context_1 = require("./tool-trace-context");
function formatToolError(error) {
    if (axios_1.default.isAxiosError(error)) {
        const status = error.response?.status;
        const responseBody = error.response?.data;
        const baseMessage = error.message || 'Axios request failed';
        const statusPart = status ? ` (status ${status})` : '';
        if (typeof responseBody === 'string' && responseBody.trim()) {
            return `${baseMessage}${statusPart}: ${responseBody.trim()}`;
        }
        if (responseBody && typeof responseBody === 'object') {
            try {
                return `${baseMessage}${statusPart}: ${JSON.stringify(responseBody)}`;
            }
            catch {
                return `${baseMessage}${statusPart}`;
            }
        }
        return `${baseMessage}${statusPart}`;
    }
    if (error instanceof Error)
        return error.message || error.name;
    if (typeof error === 'string')
        return error;
    try {
        return JSON.stringify(error);
    }
    catch {
        return String(error);
    }
}
function readPreset(config) {
    const value = config.preset ?? config.profile;
    if (typeof value !== "string")
        return undefined;
    const normalized = value.trim();
    return normalized || undefined;
}
const extendedOpenClawSkillToolSchema = zod_1.z.object({
    input: zod_1.z.string().optional().describe("User goal or parameters for the OPENCLAW planner."),
});
const extendedPassthroughSkillToolSchema = zod_1.z.object({}).passthrough();
const extendedSkillConfirmationField = zod_1.z.object({
    confirmed: zod_1.z
        .boolean()
        .optional()
        .describe("Set by the confirmation UI when resuming; omit for normal calls."),
});
function withOptionalConfirmationFlag(schema) {
    if (schema instanceof zod_1.z.ZodObject) {
        return schema.merge(extendedSkillConfirmationField);
    }
    return zod_1.z.intersection(schema, extendedSkillConfirmationField);
}
function buildSkillZodSchema(config, schemaProps) {
    const executionMode = config.orchestration?.mode;
    if (executionMode === "OPENCLAW" || (config.kind || "").toLowerCase() === "openclaw") {
        return withOptionalConfirmationFlag(extendedOpenClawSkillToolSchema);
    }
    if (!schemaProps || Object.keys(schemaProps).length === 0) {
        return withOptionalConfirmationFlag(extendedPassthroughSkillToolSchema);
    }
    const shape = {};
    for (const [key, prop] of Object.entries(schemaProps)) {
        const desc = prop.description || key;
        const hasConst = "const" in prop;
        const hasDefault = "default" in prop;
        const isRequired = prop.required === true;
        const rawEnum = prop.enum;
        const enumValues = Array.isArray(rawEnum)
            ? rawEnum.map((v) => (typeof v === "object" && v !== null && "value" in v) ? v.value : v)
            : undefined;
        let descWithMeta = desc;
        if (hasConst)
            descWithMeta += " (fixed value, omit)";
        let field;
        if (prop.type === "number" || prop.type === "integer") {
            let baseField;
            if (enumValues && enumValues.length > 0) {
                const enumStrs = enumValues.map(String);
                baseField = zod_1.z.enum([enumStrs[0], ...enumStrs.slice(1)]).transform(Number);
            }
            else {
                baseField = zod_1.z.number();
            }
            if (hasDefault) {
                field = baseField.default(prop.default);
            }
            else if (isRequired) {
                field = baseField;
            }
            else {
                field = baseField.optional();
            }
        }
        else if (prop.type === "boolean") {
            let baseField = zod_1.z.boolean();
            if (hasDefault) {
                field = baseField.default(prop.default);
            }
            else if (isRequired) {
                field = baseField;
            }
            else {
                field = baseField.optional();
            }
        }
        else {
            let baseField;
            if (enumValues && enumValues.length > 0) {
                const enumStrs = enumValues.map(String);
                baseField = zod_1.z.enum([enumStrs[0], ...enumStrs.slice(1)]);
            }
            else {
                baseField = zod_1.z.string();
            }
            if (hasDefault) {
                field = baseField.default(prop.default);
            }
            else if (isRequired) {
                field = baseField;
            }
            else {
                field = baseField.optional();
            }
        }
        shape[key] = field.describe(descWithMeta);
    }
    return withOptionalConfirmationFlag(zod_1.z.object(shape).passthrough());
}
const gatewayExtendedToolRegistry = new Map();
const gatewayExtendedToolIdRegistry = new Map();
function normalizeToolName(name, id) {
    let processedName = name;
    if (/[\u4e00-\u9fff]/.test(name)) {
        try {
            processedName = (0, pinyin_pro_1.pinyin)(name, { toneType: "none", type: "array" }).join(" ");
        }
        catch {
            processedName = name;
        }
    }
    const normalized = processedName
        .trim()
        .toLowerCase()
        .replace(/[^a-z0-9]+/g, "_")
        .replace(/^_+|_+$/g, "");
    const prefix = "extended_";
    const maxLen = 64 - prefix.length;
    const truncated = normalized.substring(0, maxLen).replace(/_+$/, "");
    return truncated ? `${prefix}${truncated}` : `extended_skill_${id}`;
}
function normalizeExecutionMode(executionMode) {
    return executionMode?.toUpperCase() === "OPENCLAW" ? "OPENCLAW" : "CONFIG";
}
function localizeExecutionMode(executionMode) {
    return normalizeExecutionMode(executionMode) === "OPENCLAW" ? "自主规划" : "预配置";
}
function registerGatewayToolMetadata(toolName, skill) {
    const metadata = {
        displayName: skill.name || `skill_${skill.id}`,
        executionMode: normalizeExecutionMode(skill.executionMode),
        executionLabel: localizeExecutionMode(skill.executionMode),
    };
    gatewayExtendedToolRegistry.set(toolName, metadata);
    gatewayExtendedToolRegistry.set(toolName.replace(/_/g, "-"), metadata);
    gatewayExtendedToolIdRegistry.set(skill.id, metadata);
}
function describeGatewayExtendedTool(toolName) {
    const metadata = gatewayExtendedToolRegistry.get(toolName)
        ?? gatewayExtendedToolRegistry.get(toolName.replace(/-/g, "_"))
        ?? gatewayExtendedToolRegistry.get(toolName.replace(/_/g, "-"));
    if (metadata) {
        return {
            displayName: metadata.displayName,
            kind: 'skill',
            executionMode: metadata.executionMode,
            executionLabel: metadata.executionLabel,
        };
    }
    const idMatch = toolName.match(/(\d+)$/);
    if (!idMatch)
        return null;
    const skillId = Number(idMatch[1]);
    if (!Number.isFinite(skillId))
        return null;
    const idDisplayName = gatewayExtendedToolIdRegistry.get(skillId);
    if (!idDisplayName)
        return null;
    return {
        displayName: idDisplayName.displayName,
        kind: 'skill',
        executionMode: idDisplayName.executionMode,
        executionLabel: idDisplayName.executionLabel,
    };
}
function normalizeParameterBindingValue(raw) {
    if (raw === "jsonBody" || raw === "query" || raw === "formBody")
        return raw;
    return undefined;
}
function normalizeExtendedConfig(cfg) {
    const next = { ...cfg };
    const pb = normalizeParameterBindingValue(cfg.parameterBinding);
    if (pb) {
        next.parameterBinding = pb;
    }
    else {
        delete next.parameterBinding;
    }
    const rawPc = cfg.parameterContract;
    if (typeof rawPc === "string" && rawPc.trim()) {
        try {
            const parsed = JSON.parse(rawPc);
            if (parsed && typeof parsed === "object") {
                next.parameterContract = parsed;
            }
        }
        catch {
        }
    }
    return next;
}
function parseSkillConfig(skill) {
    if (!skill.configuration || !skill.configuration.trim())
        return {};
    try {
        const parsed = JSON.parse(skill.configuration);
        if (!parsed || typeof parsed !== "object")
            return {};
        return normalizeExtendedConfig(parsed);
    }
    catch {
        return {};
    }
}
function normalizeParameterContractRequired(contract) {
    const out = { ...contract };
    const props = out.properties;
    if (!props)
        return out;
    const existingRequired = Array.isArray(out.required) ? out.required : [];
    const requiredSet = new Set(existingRequired);
    const normalizedProps = {};
    for (const [key, prop] of Object.entries(props)) {
        const p = { ...prop };
        if (p.required === true) {
            requiredSet.add(key);
            delete p.required;
        }
        normalizedProps[key] = p;
    }
    out.properties = normalizedProps;
    if (requiredSet.size > 0) {
        out.required = Array.from(requiredSet);
    }
    else {
        delete out.required;
    }
    return out;
}
function normalizeGeneratedOperation(value) {
    const normalized = value
        .trim()
        .toLowerCase()
        .replace(/[^a-z0-9]+/g, "_")
        .replace(/^_+|_+$/g, "");
    return normalized || "api_request";
}
function sanitizeConfigForDisplay(config) {
    return config;
}
async function executeOpenClawSkill(plannerModel, parentToolName, input, config, availableTools) {
    const orchestrationMode = config.orchestration?.mode || "serial";
    if (orchestrationMode !== "serial") {
        return JSON.stringify({ error: `Unsupported OPENCLAW orchestration mode: ${orchestrationMode}` });
    }
    const availableToolLookup = new Map();
    availableTools.forEach((tool) => {
        availableToolLookup.set(tool.name, tool);
        availableToolLookup.set(tool.name.replace(/_/g, "-"), tool);
        availableToolLookup.set(tool.name.replace(/-/g, "_"), tool);
        const metadata = describeGatewayExtendedTool(tool.name);
        if (metadata?.displayName) {
            availableToolLookup.set(metadata.displayName, tool);
        }
    });
    const allowedTools = (0, openclaw_executor_1.resolveAllowedTools)(config.allowedTools, availableToolLookup);
    const missingTools = (config.allowedTools || []).filter((name) => (!availableToolLookup.get(name)
        && !availableToolLookup.get(name.trim())
        && !availableToolLookup.get(name.replace(/-/g, "_"))));
    if (missingTools.length > 0) {
        return JSON.stringify({ error: `OPENCLAW skill is missing required tools: ${missingTools.join(", ")}` });
    }
    const parentToolId = (0, tool_trace_context_1.getActiveParentToolId)(parentToolName);
    const planner = allowedTools.length > 0
        ? (plannerModel && typeof plannerModel.bindTools === "function" ? plannerModel.bindTools(allowedTools) : null)
        : plannerModel;
    if (!planner || typeof planner.invoke !== "function") {
        return JSON.stringify({ error: "OPENCLAW planner model is unavailable." });
    }
    const systemPrompt = [
        config.systemPrompt || "You are an autonomous planning skill.",
        "You MUST call exactly ONE tool per turn, in order. Never emit multiple tool_calls in a single response.",
        "Do not skip required tool calls.",
        "For the compute tool, use structured arguments: operation (enum) and operands (array)—see tool schema. Do not nest under an input key.",
        "If the user's input is ambiguous or cannot be reliably parsed, ask a clarification question instead of guessing.",
    ].join("\n\n");
    const messages = [
        { role: "system", content: systemPrompt },
        { role: "user", content: input || "{}" },
    ];
    for (let round = 0; round < 6; round += 1) {
        const response = await planner.invoke(messages);
        const rawToolCalls = Array.isArray(response?.tool_calls) ? response.tool_calls : [];
        let toolCalls = rawToolCalls;
        if (orchestrationMode === "serial" && rawToolCalls.length > 1) {
            toolCalls = [rawToolCalls[0]];
            messages.push(new messages_1.AIMessage({
                content: response.content ?? "",
                tool_calls: toolCalls,
            }));
        }
        else {
            messages.push(response);
        }
        if (toolCalls.length === 0) {
            const content = response?.content;
            if (typeof content === "string")
                return content;
            if (Array.isArray(content)) {
                return content
                    .map((part) => typeof part === "string" ? part : part?.text || "")
                    .join("");
            }
            return JSON.stringify(content ?? "");
        }
        for (const toolCall of toolCalls) {
            const tool = allowedTools.find((candidate) => candidate.name === toolCall.name);
            if (!tool) {
                return JSON.stringify({ error: `OPENCLAW skill tried to call unauthorized tool: ${toolCall.name}` });
            }
            const childToolId = typeof toolCall.id === "string" && toolCall.id.trim()
                ? toolCall.id
                : `${parentToolName}:${tool.name}:${round}`;
            const childDisplayName = describeGatewayExtendedTool(tool.name)?.displayName || tool.name;
            (0, tool_trace_context_1.emitToolTraceEvent)({
                type: "tool_status",
                toolId: childToolId,
                toolName: tool.name,
                displayName: childDisplayName,
                kind: describeGatewayExtendedTool(tool.name)?.kind || "tool",
                status: "running",
                parentToolId,
                parentToolName,
                arguments: (0, tool_trace_context_1.sanitizeToolTraceArguments)(toolCall.args || {}),
            });
            try {
                const result = await (0, openclaw_executor_1.invokeToolDirect)(tool, toolCall.args || {});
                (0, tool_trace_context_1.emitToolTraceEvent)({
                    type: "tool_status",
                    toolId: childToolId,
                    toolName: tool.name,
                    displayName: childDisplayName,
                    kind: describeGatewayExtendedTool(tool.name)?.kind || "tool",
                    status: "completed",
                    parentToolId,
                    parentToolName,
                    summary: (0, openclaw_executor_1.summarizeToolResult)(result),
                    result: (0, tool_trace_context_1.sanitizeToolResultForTrace)(result),
                });
                const resolvedCallId = typeof toolCall.id === "string" && toolCall.id.trim() ? toolCall.id : childToolId;
                messages.push({
                    role: "tool",
                    tool_call_id: resolvedCallId,
                    content: result,
                });
            }
            catch (error) {
                if ((0, langgraph_1.isGraphInterrupt)(error))
                    throw error;
                const message = formatToolError(error);
                (0, tool_trace_context_1.emitToolTraceEvent)({
                    type: "tool_status",
                    toolId: childToolId,
                    toolName: tool.name,
                    displayName: childDisplayName,
                    kind: describeGatewayExtendedTool(tool.name)?.kind || "tool",
                    status: "failed",
                    parentToolId,
                    parentToolName,
                    summary: message,
                    result: (0, tool_trace_context_1.sanitizeToolResultForTrace)(message),
                });
                const resolvedCallId = typeof toolCall.id === "string" && toolCall.id.trim() ? toolCall.id : childToolId;
                messages.push({
                    role: "tool",
                    tool_call_id: resolvedCallId,
                    content: JSON.stringify({ error: message }),
                });
            }
        }
    }
    return JSON.stringify({ error: "OPENCLAW skill exceeded the maximum planning steps." });
}
function gatewaySkillMutationHeaders(apiToken, userId, sessionId) {
    const headers = {
        "X-Agent-Token": apiToken,
        "Content-Type": "application/json",
    };
    if (userId && String(userId).trim()) {
        headers["X-User-Id"] = String(userId).trim();
    }
    if (sessionId && String(sessionId).trim()) {
        headers["X-Session-Id"] = String(sessionId).trim();
    }
    return headers;
}
function gatewaySkillReadHeaders(apiToken, userId) {
    const headers = {
        "X-Agent-Token": apiToken,
    };
    if (userId && String(userId).trim()) {
        headers["X-User-Id"] = String(userId).trim();
    }
    return headers;
}
async function loadGatewayExtendedTools(gatewayUrl, apiToken, userId, options) {
    try {
        const listHeaders = gatewaySkillReadHeaders(apiToken, userId);
        const response = await axios_1.default.get(`${gatewayUrl}/api/skills`, {
            headers: listHeaders,
        });
        const skills = Array.isArray(response.data) ? response.data : [];
        const extensionSkills = skills.filter((skill) => skill.enabled && (skill.type || "").toUpperCase() === "EXTENSION");
        const toolLookup = new Map();
        (options?.availableTools || []).forEach((tool) => {
            toolLookup.set(tool.name, tool);
        });
        const resolvedTools = [];
        for (const skill of extensionSkills) {
            console.log(`[DEBUG] skill ${skill.id} configuration:`, skill.configuration);
            let workingSkill = skill;
            let config = skill.configuration ? parseSkillConfig(skill) : {};
            if (!skill.configuration?.trim()) {
                try {
                    const detailResponse = await axios_1.default.get(`${gatewayUrl}/api/skills/${skill.id}`, {
                        headers: gatewaySkillReadHeaders(apiToken, userId),
                    });
                    workingSkill = detailResponse.data;
                    config = parseSkillConfig(workingSkill);
                }
                catch {
                }
            }
            const toolName = normalizeToolName(skill.name || `skill_${skill.id}`, skill.id);
            registerGatewayToolMetadata(toolName, workingSkill);
            let toolDescription = workingSkill.description || `Execute extended skill: ${workingSkill.name}`;
            if (workingSkill.requiresConfirmation) {
                toolDescription +=
                    " If this skill requires confirmation, approval happens via the chat UI buttons only; do not instruct the user to type \"confirm\" or to send JSON with confirmed:true.";
            }
            const zodSchema = buildSkillZodSchema(config, skill.schemaProperties);
            const structuredTool = new tools_1.DynamicStructuredTool({
                name: toolName,
                description: toolDescription,
                schema: zodSchema,
                func: async (args, _runManager, runConfig) => {
                    try {
                        let execInput = args;
                        let currentSkill = workingSkill;
                        let currentConfig = config;
                        try {
                            const detailResponse = await axios_1.default.get(`${gatewayUrl}/api/skills/${skill.id}`, {
                                headers: gatewaySkillReadHeaders(apiToken, userId),
                            });
                            currentSkill = detailResponse.data;
                            currentConfig = parseSkillConfig(currentSkill);
                        }
                        catch {
                        }
                        const executionMode = normalizeExecutionMode(currentSkill.executionMode);
                        if (executionMode === "OPENCLAW" || (currentConfig.kind || "").toLowerCase() === "openclaw") {
                            const openClawInput = typeof execInput === "string"
                                ? execInput
                                : execInput && typeof execInput === "object" && typeof execInput.input === "string"
                                    ? String(execInput.input)
                                    : JSON.stringify(execInput ?? {});
                            return await executeOpenClawSkill(options?.plannerModel, toolName, openClawInput, currentConfig, Array.from(toolLookup.values()));
                        }
                        const executeUrl = `${gatewayUrl}/api/skills/execute`;
                        const executeSessionId = options?.sessionId ?? runConfig?.configurable?.thread_id
                            ? String(options?.sessionId ?? runConfig?.configurable?.thread_id)
                            : undefined;
                        const executeHeaders = gatewaySkillMutationHeaders(apiToken, userId, executeSessionId);
                        const parameters = execInput && typeof execInput === "object" && !Array.isArray(execInput)
                            ? execInput
                            : {};
                        const executePayload = { skillId: currentSkill.id, parameters };
                        let executeResponse;
                        try {
                            executeResponse = await axios_1.default.post(executeUrl, executePayload, { headers: executeHeaders });
                        }
                        catch (apiError) {
                            return `Error executing extended skill "${skill.name}": ${formatToolError(apiError)}`;
                        }
                        const responseData = executeResponse.data;
                        if (responseData.status === "CONFIRMATION_REQUIRED") {
                            const toolCallId = runConfig?.configurable?.thread_id
                                ? `${String(runConfig.configurable.thread_id)}:${toolName}`
                                : `${toolName}:confirm`;
                            const resume = (0, langgraph_1.interrupt)({
                                kind: "extended_skill_confirmation",
                                toolName,
                                toolCallId,
                                skillName: String(responseData.skillName || currentSkill.name || toolName),
                                skillId: currentSkill.id,
                                summary: `Execute skill: ${responseData.skillName || currentSkill.name || toolName}`,
                                details: "",
                                parametersPreview: parameters,
                                gatewayRequestId: String(responseData.requestId || ""),
                            });
                            const result = resume;
                            if (!result.confirmed) {
                                return JSON.stringify({ status: "CANCELLED", message: "User cancelled the skill execution." });
                            }
                            const confirmedPayload = {
                                skillId: currentSkill.id,
                                parameters: parameters,
                                confirmed: true,
                                requestId: responseData.requestId,
                                ...(result.adjustedParams ? { adjustedParams: result.adjustedParams } : {}),
                            };
                            let confirmedResponse;
                            try {
                                confirmedResponse = await axios_1.default.post(executeUrl, confirmedPayload, { headers: executeHeaders });
                            }
                            catch (confirmedError) {
                                return `Error executing extended skill "${skill.name}" after confirmation: ${formatToolError(confirmedError)}`;
                            }
                            return typeof confirmedResponse.data === "string"
                                ? confirmedResponse.data
                                : JSON.stringify(confirmedResponse.data);
                        }
                        return typeof executeResponse.data === "string"
                            ? executeResponse.data
                            : JSON.stringify(executeResponse.data);
                    }
                    catch (error) {
                        if ((0, langgraph_1.isGraphInterrupt)(error))
                            throw error;
                        return `Error executing extended skill "${skill.name}": ${formatToolError(error)}`;
                    }
                },
            });
            resolvedTools.push(structuredTool);
            toolLookup.set(structuredTool.name, structuredTool);
            if (skill.name) {
                toolLookup.set(skill.name, structuredTool);
            }
        }
        return resolvedTools;
    }
    catch (error) {
        console.error("[agent-core] Failed to load extended skills from gateway:", formatToolError(error));
        return [];
    }
}
function buildConfirmedToolInputString(args) {
    return JSON.stringify(buildConfirmedToolArgs(args));
}
function buildConfirmedToolArgs(args) {
    if (args === undefined || args === null) {
        return { confirmed: true };
    }
    if (typeof args === "object" && !Array.isArray(args)) {
        return { ...args, confirmed: true };
    }
    if (typeof args === "string") {
        const trimmed = args.trim();
        if (!trimmed)
            return { confirmed: true };
        try {
            const o = JSON.parse(trimmed);
            if (o && typeof o === "object" && !Array.isArray(o)) {
                return { ...o, confirmed: true };
            }
        }
        catch {
            return { confirmed: true, input: trimmed };
        }
        return { confirmed: true, input: trimmed };
    }
    return { confirmed: true, input: String(args) };
}
async function invokeExtendedSkillWithConfirmed(gatewayUrl, apiToken, userId, toolName, toolArguments, options) {
    const extendedTools = await loadGatewayExtendedTools(gatewayUrl, apiToken, userId, {
        plannerModel: options.plannerModel,
        availableTools: options.availableTools,
    });
    const underscore = toolName.replace(/-/g, "_");
    const tool = extendedTools.find((t) => t.name === toolName)
        ?? extendedTools.find((t) => t.name === underscore);
    if (!tool) {
        return JSON.stringify({ error: `Extended skill tool not found: ${toolName}` });
    }
    const merged = buildConfirmedToolArgs(toolArguments);
    const raw = await tool.invoke(merged);
    return typeof raw === "string" ? raw : JSON.stringify(raw);
}
class JavaComputeTool extends tools_1.DynamicStructuredTool {
    constructor(gatewayUrl, apiToken, options) {
        const dispatch = options?.dispatch ?? "legacy";
        const baseUrl = gatewayUrl.replace(/\/+$/, "");
        super({
            name: "compute",
            description: "Math and date operations via Skill Gateway. Supply operation and operands as separate fields (see parameter schema). "
                + "Gateway body is { operation, operands }—do not wrap them in an extra input string.",
            schema: exports.computeToolInputSchema,
            func: async (args) => {
                try {
                    const headers = {
                        "X-Agent-Token": apiToken,
                        "Content-Type": "application/json",
                    };
                    const payload = dispatch === "gateway"
                        ? { toolName: "compute", arguments: { operation: args.operation, operands: args.operands } }
                        : { operation: args.operation, operands: args.operands };
                    const url = dispatch === "gateway"
                        ? `${baseUrl}/api/system-skills/execute`
                        : `${gatewayUrl}/api/skills/compute`;
                    const response = await axios_1.default.post(url, payload, { headers });
                    return JSON.stringify(response.data);
                }
                catch (error) {
                    return `Error executing compute: ${formatToolError(error)}`;
                }
            },
        });
    }
}
exports.JavaComputeTool = JavaComputeTool;
class JavaServerLookupTool extends tools_1.DynamicStructuredTool {
    constructor(gatewayUrl, apiToken, userId) {
        super({
            name: "server_lookup",
            description: "Finds up to 5 server candidates (`id` + `name`) for a user-entered serverName (relevance-ordered; connection secrets stay in Gateway DB only, not returned). " +
                "If exactly one row, use its `id` for linux_script_executor next; if several, ask the user to pick an `id`. " +
                "Provide `serverName` (do NOT wrap in a single input string).",
            schema: serverLookupToolInputSchema,
            func: async (args) => {
                try {
                    const headers = {
                        "X-Agent-Token": apiToken,
                        "Content-Type": "application/json",
                    };
                    if (userId) {
                        headers["X-User-Id"] = userId;
                    }
                    const response = await axios_1.default.get(`${gatewayUrl}/api/skills/server-lookup`, {
                        headers,
                        params: { serverName: args.serverName },
                    });
                    return JSON.stringify(response.data);
                }
                catch (error) {
                    return `Error looking up server: ${formatToolError(error)}`;
                }
            },
        });
    }
}
exports.JavaServerLookupTool = JavaServerLookupTool;
const JAVA_API_TOOL_DESCRIPTION = "Calls an external API via the Java gateway. " +
    "Provide url, method, headers, and body as separate fields (do NOT wrap everything in a single JSON string under 'input'). " +
    "If an extension skill covers the same HTTP capability, use that extension tool instead of this built-in.";
class JavaApiTool extends tools_1.DynamicStructuredTool {
    constructor(gatewayUrl, apiToken, options) {
        const dispatch = options?.dispatch ?? "legacy";
        const baseUrl = gatewayUrl.replace(/\/+$/, "");
        super({
            name: "api_caller",
            description: JAVA_API_TOOL_DESCRIPTION,
            schema: exports.apiCallerToolInputSchema,
            func: async (args) => {
                try {
                    const headers = {
                        "X-Agent-Token": apiToken,
                        "Content-Type": "application/json",
                    };
                    const url = dispatch === "gateway"
                        ? `${baseUrl}/api/system-skills/execute`
                        : `${gatewayUrl}/api/skills/api`;
                    const body = dispatch === "gateway" ? { toolName: "api_caller", arguments: args } : args;
                    const response = await axios_1.default.post(url, body, { headers });
                    return JSON.stringify(response.data);
                }
                catch (error) {
                    return `Error calling API: ${formatToolError(error)}`;
                }
            },
        });
    }
}
exports.JavaApiTool = JavaApiTool;
//# sourceMappingURL=java-skills.js.map