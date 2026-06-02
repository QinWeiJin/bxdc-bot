"use strict";
var __decorate = (this && this.__decorate) || function (decorators, target, key, desc) {
    var c = arguments.length, r = c < 3 ? target : desc === null ? desc = Object.getOwnPropertyDescriptor(target, key) : desc, d;
    if (typeof Reflect === "object" && typeof Reflect.decorate === "function") r = Reflect.decorate(decorators, target, key, desc);
    else for (var i = decorators.length - 1; i >= 0; i--) if (d = decorators[i]) r = (c < 3 ? d(r) : c > 3 ? d(target, key, r) : d(target, key)) || r;
    return c > 3 && r && Object.defineProperty(target, key, r), r;
};
var __metadata = (this && this.__metadata) || function (k, v) {
    if (typeof Reflect === "object" && typeof Reflect.metadata === "function") return Reflect.metadata(k, v);
};
var __param = (this && this.__param) || function (paramIndex, decorator) {
    return function (target, key) { decorator(target, key, paramIndex); }
};
Object.defineProperty(exports, "__esModule", { value: true });
exports.AgentController = void 0;
const common_1 = require("@nestjs/common");
const rxjs_1 = require("rxjs");
const agent_1 = require("../agent/agent");
const memory_service_1 = require("../mem/memory.service");
const skill_manager_1 = require("../skills/skill.manager");
const logger_service_1 = require("../utils/logger.service");
const java_skills_1 = require("../tools/java-skills");
const tool_trace_context_1 = require("../tools/tool-trace-context");
const llm_merge_1 = require("../utils/llm-merge");
const agent_run_raw_log_1 = require("../utils/agent-run-raw-log");
const history_sanitize_1 = require("../utils/history-sanitize");
const langgraph_1 = require("@langchain/langgraph");
const prompts_1 = require("../prompts");
const conversation_logger_1 = require("../utils/conversation-logger");
function unwrapLangGraphStreamPayload(raw) {
    if (Array.isArray(raw) && raw.length >= 2 && typeof raw[0] === 'string') {
        return raw[1];
    }
    return raw;
}
function extractInterruptEntries(payload) {
    if (!payload || typeof payload !== 'object')
        return [];
    const p = payload;
    const arr = p[langgraph_1.INTERRUPT] ?? p.__interrupt__;
    return Array.isArray(arr) ? arr : [];
}
function stripInterruptForClient(payload) {
    if (!payload || typeof payload !== 'object')
        return payload;
    const p = payload;
    if (!(langgraph_1.INTERRUPT in p) && !('__interrupt__' in p))
        return payload;
    const next = { ...p };
    delete next[langgraph_1.INTERRUPT];
    delete next.__interrupt__;
    return Object.keys(next).length > 0 ? next : null;
}
const CONFIRMATION_TIMEOUT_MS = 5 * 60 * 1000;
const pendingConfirmations = new Map();
function confirmationKey(sessionId, toolCallId) {
    return `${sessionId}:${toolCallId}`;
}
function asArray(value) {
    if (!value)
        return [];
    return Array.isArray(value) ? value : [value];
}
function getChunkMessages(chunk) {
    if (!chunk || typeof chunk !== 'object')
        return [];
    return [
        ...asArray(chunk.agent?.messages),
        ...asArray(chunk.tools?.messages),
        ...asArray(chunk.messages),
    ];
}
function inferMessageKind(message) {
    const nestedType = message?.kwargs?.type;
    if (typeof nestedType === 'string')
        return nestedType.toLowerCase();
    const explicitType = message?.type;
    if (typeof explicitType === 'string')
        return explicitType.toLowerCase();
    const nestedRole = message?.kwargs?.role;
    if (typeof nestedRole === 'string')
        return nestedRole.toLowerCase();
    const role = message?.role;
    if (typeof role === 'string')
        return role.toLowerCase();
    const id = message?.id;
    if (typeof id === 'string')
        return id.toLowerCase();
    if (Array.isArray(id))
        return id.join('.').toLowerCase();
    if (message?.content !== undefined)
        return 'aimessagechunk';
    return '';
}
function isAssistantMessage(message) {
    const kind = inferMessageKind(message);
    return kind.includes('assistant') || kind.includes('aimessage') || kind === 'ai';
}
function isToolMessage(message) {
    const kind = inferMessageKind(message);
    return kind.includes('toolmessage') || kind === 'tool';
}
function extractContent(content) {
    if (typeof content === 'string')
        return content;
    if (Array.isArray(content)) {
        const text = content
            .map((part) => {
            if (typeof part === 'string')
                return part;
            if (part && typeof part === 'object' && typeof part.text === 'string') {
                return part.text;
            }
            return '';
        })
            .join('');
        return text || null;
    }
    return null;
}
function getMessageContent(message) {
    return extractContent(message?.kwargs?.content)
        ?? extractContent(message?.lc_kwargs?.content)
        ?? extractContent(message?.content)
        ?? null;
}
function getToolCallEntries(message) {
    return [
        ...asArray(message?.tool_calls),
        ...asArray(message?.kwargs?.tool_calls),
        ...asArray(message?.kwargs?.additional_kwargs?.tool_calls),
        ...asArray(message?.additional_kwargs?.tool_calls),
    ];
}
function normalizeToolName(toolCall, fallback) {
    const name = toolCall?.name
        ?? toolCall?.function?.name
        ?? toolCall?.kwargs?.name
        ?? fallback;
    return typeof name === 'string' && name.trim() ? name.trim() : null;
}
function normalizeToolId(toolCall, fallback) {
    const id = toolCall?.id ?? toolCall?.tool_call_id ?? toolCall?.function?.id;
    return typeof id === 'string' && id.trim() ? id.trim() : fallback;
}
function normalizeToolIdFromToolMessage(message, messageIndex, toolName) {
    const id = message?.tool_call_id
        ?? message?.kwargs?.tool_call_id
        ?? message?.lc_kwargs?.tool_call_id;
    if (typeof id === 'string' && id.trim())
        return id.trim();
    return `${toolName}:toolmsg:${messageIndex}`;
}
function parseToolArguments(rawArguments) {
    if (typeof rawArguments !== 'string')
        return rawArguments;
    const trimmed = rawArguments.trim();
    if (!trimmed)
        return trimmed;
    try {
        return JSON.parse(trimmed);
    }
    catch {
        return trimmed;
    }
}
function extractToolArguments(toolCall) {
    return parseToolArguments(toolCall?.args
        ?? toolCall?.arguments
        ?? toolCall?.function?.arguments
        ?? toolCall?.kwargs?.args
        ?? toolCall?.kwargs?.arguments);
}
function normalizeToolStatus(message) {
    const status = message?.status ?? message?.kwargs?.status;
    if (status === 'error' || status === 'failed')
        return 'failed';
    const content = getMessageContent(message)?.toLowerCase() ?? '';
    if (content.startsWith('error'))
        return 'failed';
    return 'completed';
}
function extractStartedToolCalls(message, messageIndex) {
    if (!isAssistantMessage(message))
        return [];
    return getToolCallEntries(message)
        .map((toolCall, index) => {
        const toolName = normalizeToolName(toolCall);
        if (!toolName)
            return null;
        return {
            toolId: normalizeToolId(toolCall, `${toolName}:${messageIndex}:${index}`),
            toolName,
            status: 'running',
            arguments: (0, tool_trace_context_1.sanitizeToolTraceArguments)(extractToolArguments(toolCall)),
        };
    })
        .filter(Boolean);
}
function extractCompletedToolCall(message, messageIndex) {
    if (!isToolMessage(message))
        return null;
    const toolName = normalizeToolName(message, message?.kwargs?.name);
    if (!toolName)
        return null;
    const toolId = normalizeToolIdFromToolMessage(message, messageIndex, toolName);
    const status = normalizeToolStatus(message);
    const content = getMessageContent(message);
    const result = content != null && String(content).length > 0
        ? (0, tool_trace_context_1.sanitizeToolResultForTrace)(String(content))
        : undefined;
    return {
        toolId,
        toolName,
        status,
        result,
    };
}
function toolMessageContentIsCancelledSkill(content) {
    if (!content)
        return false;
    const t = content.trim();
    if (!t.includes('CANCELLED'))
        return false;
    try {
        const j = JSON.parse(t);
        return j?.status === 'CANCELLED';
    }
    catch {
        return false;
    }
}
function chunkContainsCancelledToolForId(chunks, toolCallId) {
    for (const chunk of chunks) {
        if (!chunk || typeof chunk !== 'object')
            continue;
        const messages = getChunkMessages(chunk);
        for (let i = 0; i < messages.length; i++) {
            const msg = messages[i];
            if (!isToolMessage(msg))
                continue;
            const toolName = normalizeToolName(msg, msg?.kwargs?.name) ?? 'unknown';
            const tid = normalizeToolIdFromToolMessage(msg, i, toolName);
            if (tid !== toolCallId)
                continue;
            if (toolMessageContentIsCancelledSkill(getMessageContent(msg)))
                return true;
        }
    }
    return false;
}
let AgentController = class AgentController {
    memoryService;
    skillManager;
    logger;
    constructor(memoryService, skillManager, logger) {
        this.memoryService = memoryService;
        this.skillManager = skillManager;
        this.logger = logger;
    }
    emitToolEvents(subject, chunk, seenToolStatuses, lastToolArguments, lastEmittedToolResult, toolCallStartTimes, conversationLogger, traceId, sessionId, userId) {
        const chunkMessages = getChunkMessages(chunk);
        for (let messageIndex = 0; messageIndex < chunkMessages.length; messageIndex += 1) {
            const message = chunkMessages[messageIndex];
            const startedCalls = extractStartedToolCalls(message, messageIndex);
            for (const startedCall of startedCalls) {
                this.emitToolEvent(subject, startedCall, seenToolStatuses, lastToolArguments, lastEmittedToolResult, toolCallStartTimes, conversationLogger, traceId, sessionId, userId);
            }
            const completedCall = extractCompletedToolCall(message, messageIndex);
            if (completedCall) {
                this.emitToolEvent(subject, completedCall, seenToolStatuses, lastToolArguments, lastEmittedToolResult, toolCallStartTimes, conversationLogger, traceId, sessionId, userId);
            }
        }
    }
    emitToolEvent(subject, toolCall, seenToolStatuses, lastToolArguments, lastEmittedToolResult, toolCallStartTimes, conversationLogger, traceId, sessionId, userId) {
        const previousStatus = seenToolStatuses.get(toolCall.toolId);
        const prevEmittedResult = lastEmittedToolResult.get(toolCall.toolId);
        if (previousStatus === toolCall.status) {
            const canReemitCompletedWithResult = toolCall.status === 'completed'
                && toolCall.result !== undefined
                && toolCall.result !== prevEmittedResult;
            if (!canReemitCompletedWithResult) {
                return;
            }
        }
        seenToolStatuses.set(toolCall.toolId, toolCall.status);
        if (toolCall.result !== undefined) {
            lastEmittedToolResult.set(toolCall.toolId, toolCall.result);
        }
        const resolvedArguments = toolCall.arguments !== undefined
            ? toolCall.arguments
            : lastToolArguments.get(toolCall.toolId);
        if (toolCall.arguments !== undefined) {
            lastToolArguments.set(toolCall.toolId, toolCall.arguments);
        }
        const gatewayToolInfo = (0, java_skills_1.describeGatewayExtendedTool)(toolCall.toolName);
        const toolInfo = gatewayToolInfo ?? this.skillManager.describeTool(toolCall.toolName);
        const event = {
            type: 'tool_status',
            toolId: toolCall.toolId,
            toolName: toolCall.toolName,
            displayName: toolInfo.displayName,
            kind: toolInfo.kind,
            status: toolCall.status,
            arguments: (0, tool_trace_context_1.sanitizeToolTraceArguments)(resolvedArguments),
            ...(toolCall.result !== undefined ? { result: toolCall.result } : {}),
            executionMode: gatewayToolInfo?.executionMode,
            executionLabel: gatewayToolInfo?.executionLabel,
        };
        if (toolCall.status === 'running') {
            (0, tool_trace_context_1.setActiveParentToolId)(toolCall.toolName, toolCall.toolId);
            toolCallStartTimes.set(toolCall.toolId, Date.now());
            console.log(`[ToolCallLog] Tool started: ${toolCall.toolName}, toolId: ${toolCall.toolId}, sessionId: ${sessionId}`);
        }
        else {
            (0, tool_trace_context_1.clearActiveParentToolId)(toolCall.toolName, toolCall.toolId);
            const startTime = toolCallStartTimes.get(toolCall.toolId);
            if (startTime) {
                const durationMs = Date.now() - startTime;
                const skillName = toolCall.toolName?.replace(/^extended_/, '') || toolCall.toolName;
                console.log(`[ToolCallLog] Tool completed: ${toolCall.toolName}, toolId: ${toolCall.toolId}, status: ${toolCall.status}, duration: ${durationMs}ms`);
                conversationLogger.logToolCall({
                    traceId,
                    sessionId,
                    userId,
                    toolName: toolCall.toolName,
                    skillName,
                    toolCallId: toolCall.toolId,
                    requestParams: typeof resolvedArguments === 'object' ? JSON.stringify(resolvedArguments) : undefined,
                    responseResult: typeof toolCall.result === 'string' ? toolCall.result : JSON.stringify(toolCall.result),
                    status: toolCall.status,
                    startTime: new Date(startTime).toISOString(),
                    endTime: new Date().toISOString(),
                    durationMs,
                }).then(() => {
                    console.log(`[ToolCallLog] Successfully logged tool call to database: ${toolCall.toolName}`);
                }).catch((error) => {
                    console.error(`[ToolCallLog] Failed to log tool call: ${error}`);
                });
            }
            else {
                console.log(`[ToolCallLog] No start time found for tool: ${toolCall.toolName}, toolId: ${toolCall.toolId}`);
            }
        }
        subject.next({ data: JSON.stringify(event) });
    }
    confirmAction(body) {
        const key = confirmationKey(body.sessionId, body.toolCallId);
        const pending = pendingConfirmations.get(key);
        if (!pending) {
            throw new common_1.NotFoundException('No pending confirmation found for this sessionId/toolCallId');
        }
        clearTimeout(pending.timer);
        pendingConfirmations.delete(key);
        pending.resolve({ confirmed: body.confirmed, adjustedParams: body.adjustedParams });
        return { ok: true };
    }
    runTask(body) {
        const { instruction, context, history } = body;
        const safeHistory = Array.isArray(history) ? history : [];
        const sanitizedHistory = (0, history_sanitize_1.sanitizeHistoryForAgent)(safeHistory);
        console.log('[DEBUG] Sanitized history roles:', sanitizedHistory.map(m => m?.role));
        const userId = context?.userId;
        const sessionId = context?.sessionId || 'default-session';
        (0, agent_run_raw_log_1.logAgentRunRawIfEnabled)(body, { sessionId, userId });
        const subject = new rxjs_1.Subject();
        const gatewayUrl = process.env.JAVA_GATEWAY_URL || 'http://localhost:18080';
        const apiToken = process.env.JAVA_GATEWAY_TOKEN || 'your-secure-token-here';
        let llmContext = context || {};
        if (userId) {
            fetch(`${gatewayUrl}/api/user/${userId}/llm-config-internal`)
                .then((llmConfigResponse) => {
                if (llmConfigResponse.ok) {
                    return llmConfigResponse.json();
                }
                else {
                    console.warn('[agent] Failed to fetch LLM config:', llmConfigResponse.status);
                    return null;
                }
            })
                .then((llmConfig) => {
                if (llmConfig) {
                    console.log('[agent] Fetched LLM config from skill-gateway:', {
                        llmApiBase: llmConfig.llmApiBase,
                        llmModelName: llmConfig.llmModelName,
                        hasApiKey: !!llmConfig.llmApiKey,
                    });
                    llmContext = {
                        ...llmContext,
                        llmApiBase: llmConfig.llmApiBase,
                        llmModelName: llmConfig.llmModelName,
                        llmApiKey: llmConfig.llmApiKey,
                    };
                }
                this.executeAgentTask(instruction, llmContext, sanitizedHistory, userId, sessionId, subject, gatewayUrl, apiToken);
            })
                .catch((e) => {
                console.error('[agent] Error fetching LLM config:', e);
                this.executeAgentTask(instruction, llmContext, sanitizedHistory, userId, sessionId, subject, gatewayUrl, apiToken);
            });
        }
        else {
            this.executeAgentTask(instruction, llmContext, sanitizedHistory, userId, sessionId, subject, gatewayUrl, apiToken);
        }
        return subject.asObservable();
    }
    async executeAgentTask(instruction, llmContext, sanitizedHistory, userId, sessionId, subject, gatewayUrl, apiToken) {
        const llm = (0, llm_merge_1.pickMergedLlm)(llmContext);
        const openAiApiKey = llm.apiKey;
        const modelName = llm.modelName;
        const baseUrl = llm.baseUrl;
        const skillContext = this.skillManager.buildSkillPromptContext();
        const conversationLogger = new conversation_logger_1.ConversationLogger();
        const startTime = Date.now();
        let llmRounds = 0;
        let toolCallRounds = 0;
        let isExceedMaxRound = 0;
        let isSuccess = 1;
        let errorMessage;
        let status = 'RUNNING';
        let finishReason;
        const traceId = `${sessionId}-${Date.now()}`;
        const toolNames = [];
        const skillNames = [];
        (0, tool_trace_context_1.runWithToolTraceContext)((event) => subject.next({ data: JSON.stringify(event) }), async () => {
            let fullAssistantResponse = '';
            const seenToolStatuses = new Map();
            const lastToolArguments = new Map();
            const lastEmittedToolResult = new Map();
            const toolCallStartTimes = new Map();
            try {
                const llmCallbackHandler = this.logger.createLlmCallbackHandler(sessionId, (event) => {
                    subject.next({ data: JSON.stringify(event) });
                });
                const { agent } = await agent_1.AgentFactory.createAgent(gatewayUrl, apiToken, openAiApiKey, { modelName, baseUrl, callbacks: [llmCallbackHandler], sessionId }, this.skillManager, userId);
                const memories = await this.memoryService.searchMemories(instruction, userId, 10);
                console.log(`[Memory] Retrieved ${memories.length} memories for user ${userId}`);
                if (memories.length > 0) {
                    console.log(`[Memory] First memory: ${memories[0]}`);
                }
                const memoryContext = memories.length > 0
                    ? `[User Profile & Preferences]\n${memories.map(m => `- ${m}`).join('\n')}\n\nWhen the user asks about their profile or family (e.g. 籍贯、家乡、喜好、昵称、我儿子叫啥、我女儿叫什么、我爱人叫什么), you MUST answer using the relevant information above and state it explicitly (e.g. "你儿子叫yoyo" when they ask 我儿子叫啥). Do not proactively list all facts unless asked.\n\n`
                    : '';
                const staticSystemPrompt = (0, prompts_1.buildStaticSystemPrompt)();
                const profileDetails = await this.memoryService.fetchUserProfile(userId);
                const systemContent = profileDetails
                    ? `${staticSystemPrompt}[个人特征信息]${profileDetails}`
                    : staticSystemPrompt;
                const allowedHistoryRoles = new Set(['user', 'assistant']);
                const validHistory = sanitizedHistory
                    .map((m) => {
                    const role = m?.role;
                    if (typeof role !== 'string')
                        return null;
                    const lr = role.toLowerCase();
                    if (!allowedHistoryRoles.has(lr))
                        return null;
                    return { ...m, role: lr };
                })
                    .filter((m) => m != null);
                const userTurnContentWithSystem = `System:\n${systemContent}\n\n${skillContext}${memoryContext}User Instruction:\n${instruction}`;
                const messages = [
                    ...validHistory,
                    { role: 'user', content: userTurnContentWithSystem },
                ];
                console.log('[DEBUG] Final messages roles:', messages.map(m => m.role));
                console.log('[DEBUG] Final messages count:', messages.length);
                const graphConfig = { configurable: { thread_id: sessionId } };
                let stream = await agent.stream({ messages }, graphConfig);
                let iterator = stream[Symbol.asyncIterator]();
                outer: while (true) {
                    const { value: raw, done } = await iterator.next();
                    if (done)
                        break;
                    const payload = unwrapLangGraphStreamPayload(raw);
                    const interruptEntries = extractInterruptEntries(payload);
                    for (const entry of interruptEntries) {
                        const v = entry.value;
                        if (v
                            && (v.kind === 'extended_skill_confirmation' || v.kind === 'ssh_confirmation')) {
                            const gatewayInfo = v.kind === 'extended_skill_confirmation'
                                ? (0, java_skills_1.describeGatewayExtendedTool)(v.toolName)
                                : null;
                            const skillName = v.kind === 'extended_skill_confirmation'
                                ? (gatewayInfo?.displayName ?? v.toolName.replace(/^extended_/, ''))
                                : v.skillName;
                            const resolvedArgs = lastToolArguments.get(v.toolCallId) ?? v.parametersPreview;
                            subject.next({
                                data: JSON.stringify({
                                    type: 'confirmation_request',
                                    sessionId,
                                    toolCallId: v.toolCallId,
                                    toolName: v.toolName,
                                    skillName,
                                    summary: v.summary ?? `Execute skill: ${skillName}`,
                                    details: v.details ?? '',
                                    arguments: resolvedArgs,
                                }),
                            });
                            const confirmedResult = await new Promise((resolve) => {
                                const key = confirmationKey(sessionId, v.toolCallId);
                                const timer = setTimeout(() => {
                                    pendingConfirmations.delete(key);
                                    console.log(`[Confirmation] Timeout for ${key}, auto-cancelling`);
                                    resolve({ confirmed: false });
                                }, CONFIRMATION_TIMEOUT_MS);
                                pendingConfirmations.set(key, {
                                    resolve,
                                    toolCallId: v.toolCallId,
                                    toolName: v.toolName,
                                    skillName,
                                    arguments: resolvedArgs,
                                    timer,
                                });
                            });
                            if (!confirmedResult.confirmed) {
                                const ac = new AbortController();
                                const cancelResumeStream = await agent.stream(new langgraph_1.Command({ resume: { confirmed: false } }), { configurable: { thread_id: sessionId }, signal: ac.signal });
                                let cancelIter = cancelResumeStream[Symbol.asyncIterator]();
                                const MAX_CANCEL_CHUNKS = 24;
                                for (let step = 0; step < MAX_CANCEL_CHUNKS; step += 1) {
                                    let raw;
                                    try {
                                        const n = await cancelIter.next();
                                        if (n.done)
                                            break;
                                        raw = n.value;
                                    }
                                    catch (e) {
                                        const name = e && typeof e === 'object' && 'name' in e ? e.name : '';
                                        if (name === 'AbortError' || ac.signal.aborted)
                                            break;
                                        throw e;
                                    }
                                    const payload = unwrapLangGraphStreamPayload(raw);
                                    const forward = stripInterruptForClient(payload);
                                    if (forward != null) {
                                        subject.next({ data: JSON.stringify(forward) });
                                        this.emitToolEvents(subject, forward, seenToolStatuses, lastToolArguments, lastEmittedToolResult, toolCallStartTimes, conversationLogger, traceId, sessionId, userId);
                                        if (typeof forward === 'object') {
                                            const lastAssistantMessage = getChunkMessages(forward)
                                                .filter((message) => isAssistantMessage(message))
                                                .at(-1);
                                            const nextContent = getMessageContent(lastAssistantMessage);
                                            if (nextContent) {
                                                fullAssistantResponse = nextContent;
                                                subject.next({ data: JSON.stringify({ role: 'assistant', content: nextContent }) });
                                            }
                                        }
                                    }
                                    if (chunkContainsCancelledToolForId([payload, forward].filter(Boolean), v.toolCallId)) {
                                        ac.abort();
                                        break;
                                    }
                                }
                                if (!ac.signal.aborted) {
                                    ac.abort();
                                }
                                fullAssistantResponse = `已取消执行「${skillName}」。`;
                                subject.next({ data: JSON.stringify({ role: 'assistant', content: fullAssistantResponse }) });
                                break outer;
                            }
                            const resumeStream = await agent.stream(new langgraph_1.Command({ resume: { confirmed: true, adjustedParams: confirmedResult.adjustedParams } }), graphConfig);
                            iterator = resumeStream[Symbol.asyncIterator]();
                            continue outer;
                        }
                    }
                    const forward = stripInterruptForClient(payload);
                    if (forward != null) {
                        subject.next({ data: JSON.stringify(forward) });
                        this.emitToolEvents(subject, forward, seenToolStatuses, lastToolArguments, lastEmittedToolResult, toolCallStartTimes, conversationLogger, traceId, sessionId, userId);
                        if (typeof forward === 'object') {
                            const messages = getChunkMessages(forward);
                            for (const msg of messages) {
                                if (isAssistantMessage(msg)) {
                                    const toolCalls = getToolCallEntries(msg);
                                    for (const toolCall of toolCalls) {
                                        const toolName = normalizeToolName(toolCall);
                                        if (toolName && !toolNames.includes(toolName)) {
                                            toolNames.push(toolName);
                                            toolCallRounds++;
                                        }
                                        const skillName = toolName?.replace(/^extended_/, '') || toolName;
                                        if (skillName && !skillNames.includes(skillName)) {
                                            skillNames.push(skillName);
                                        }
                                    }
                                }
                            }
                        }
                        if (typeof forward === 'object') {
                            const forwardObj = forward;
                            let nextContent = null;
                            if (typeof forwardObj.content === 'string') {
                                nextContent = forwardObj.content;
                            }
                            if (!nextContent) {
                                const lastAssistantMessage = getChunkMessages(forward)
                                    .filter((message) => isAssistantMessage(message))
                                    .at(-1);
                                nextContent = getMessageContent(lastAssistantMessage);
                            }
                            if (nextContent && nextContent.length > 0) {
                                const newContent = fullAssistantResponse.length > 0 && nextContent.startsWith(fullAssistantResponse)
                                    ? nextContent.slice(fullAssistantResponse.length)
                                    : nextContent;
                                if (newContent.length > 0) {
                                    fullAssistantResponse = nextContent;
                                    subject.next({ data: JSON.stringify({ role: 'assistant', content: newContent }) });
                                }
                            }
                        }
                    }
                }
                subject.complete();
                const safeAssistantResponse = (fullAssistantResponse && typeof fullAssistantResponse === 'string') ? fullAssistantResponse : ' ';
                console.log(`[Memory] Analysis started. User: "${instruction}", Agent: "${safeAssistantResponse.slice(0, 50)}..."`);
                if (instruction) {
                    await this.memoryService.processTurn({
                        sessionId,
                        userId,
                        userText: instruction,
                        assistantText: safeAssistantResponse
                    });
                }
                status = 'COMPLETED';
                finishReason = '正常结束';
            }
            catch (error) {
                subject.next({ data: JSON.stringify({ error: error instanceof Error ? error.message : 'Unknown error' }) });
                subject.complete();
                isSuccess = 0;
                status = 'FAILED';
                errorMessage = error instanceof Error ? error.message : 'Unknown error';
                finishReason = '执行失败';
            }
            finally {
                const endTime = Date.now();
                const responseDurationSeconds = (endTime - startTime) / 1000;
                try {
                    await conversationLogger.logConversation({
                        userId: userId || '',
                        sessionId,
                        traceId,
                        responseDurationSeconds: Math.round(responseDurationSeconds * 100) / 100,
                        llmRounds,
                        toolCallRounds,
                        isExceedMaxRound,
                        isSuccess,
                        status,
                        finishReason,
                        llmModel: modelName,
                        skillName: skillNames.join(','),
                        toolName: toolNames.join(','),
                        requestData: JSON.stringify({ instruction, context: llmContext, history: sanitizedHistory }),
                        responseData: JSON.stringify({ response: fullAssistantResponse }),
                        conversationContent: JSON.stringify({
                            messages: [
                                ...sanitizedHistory,
                                { role: 'user', content: instruction },
                                { role: 'assistant', content: fullAssistantResponse }
                            ]
                        }),
                        agentVersion: process.env.npm_package_version || '1.0.0',
                        environment: process.env.NODE_ENV || 'development',
                    });
                }
                catch (logError) {
                    console.error(`[ConversationLogger] Failed to log conversation: ${logError}`);
                }
            }
        });
    }
};
exports.AgentController = AgentController;
__decorate([
    (0, common_1.Post)('confirm'),
    (0, common_1.HttpCode)(200),
    __param(0, (0, common_1.Body)()),
    __metadata("design:type", Function),
    __metadata("design:paramtypes", [Object]),
    __metadata("design:returntype", void 0)
], AgentController.prototype, "confirmAction", null);
__decorate([
    (0, common_1.Post)('run'),
    (0, common_1.Sse)(),
    __param(0, (0, common_1.Body)()),
    __metadata("design:type", Function),
    __metadata("design:paramtypes", [Object]),
    __metadata("design:returntype", rxjs_1.Observable)
], AgentController.prototype, "runTask", null);
exports.AgentController = AgentController = __decorate([
    (0, common_1.Controller)('agent'),
    __metadata("design:paramtypes", [memory_service_1.MemoryService,
        skill_manager_1.SkillManager,
        logger_service_1.LoggerService])
], AgentController);
//# sourceMappingURL=agent.controller.js.map