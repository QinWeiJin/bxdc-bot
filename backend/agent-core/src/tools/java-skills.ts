/**
 * Java Skill Gateway 工具集合
 * 
 * 模块职责：
 * 1. 提供与 Java Skill Gateway 通信的各类工具实现
 * 2. 内置工具：数学计算（compute）、服务器查询（server_lookup）
 * 3. 支持动态加载 Gateway 扩展技能（通过 `loadGatewayExtendedTools`）
 * 4. 实现技能确认机制（高风险操作需用户确认，使用 LangGraph interrupt/Command）
 * 5. 提供 OPENCLAW Skill 子规划执行
 * 
 * 内置工具列表：
 * - JavaComputeTool: 数学计算（加减乘除、阶乘、日期计算等）
 * - JavaServerLookupTool: 服务器信息查询
 * - JavaApiTool: HTTP 通用代理（`api_caller`）；**当前默认不在 `AgentFactory` 中挂载**
 * 
 * 已迁移/删除的工具：
 * - JavaSkillGeneratorTool → 已迁移至 `skill-generator.ts`
 * - JavaSshTool / JavaLinuxScriptTool → 已删除，SSH 操作统一走 SSH Extension Skill（`POST /api/skills/execute`，kind: "ssh"）
 * - OPENCLAW 辅助工具 → 已迁移至 `openclaw-executor.ts`
 * 
 * 架构说明：
 * - 扩展 Skill（API/SSH/Template）统一通过 `POST /api/skills/execute` 执行（见 `func` 函数）
 * - Gateway 端按 `kind` 分发：api → ApiProxyService, ssh → SshExecutionService, template → SkillExecutionService
 * - Agent 侧不包含定制化逻辑，仅转发参数到 Gateway
 * 
 * 确认机制：
 * - 扩展技能和危险操作需要用户确认
 * - 使用 LangGraph interrupt/Command 实现中断/恢复
 * - 确认超时时间为 5 分钟
 * 
 * 环境变量：
 * - AGENT_BUILTIN_SKILL_DISPATCH: 内置技能路由模式（legacy/gateway）
 * 
 * @module JavaSkills
 * @author Agent Core Team
 * @since 1.0.0
 */

import { AIMessage } from "@langchain/core/messages";
import type { RunnableConfig } from "@langchain/core/runnables";
import { interrupt, isGraphInterrupt } from "@langchain/langgraph";
import {
  DynamicTool,
  Tool,
  DynamicStructuredTool,
  StructuredTool,
  isStructuredTool,
} from "@langchain/core/tools";
import { z } from "zod";
import axios from "axios";
import { pinyin } from "pinyin-pro";
import { tryParseJson, invokeToolDirect, summarizeToolResult, resolveAllowedTools } from "./openclaw-executor";

export function getAgentBuiltinSkillDispatch(): "legacy" | "gateway" {
  const v = (process.env.AGENT_BUILTIN_SKILL_DISPATCH ?? "legacy").trim().toLowerCase();
  return v === "gateway" ? "gateway" : "legacy";
}

export type BuiltinSkillDispatch = "legacy" | "gateway";

/* ====================== Zod Schemas (must be before any class that uses them) ====================== */

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
] as const;

/** Exported for gateway-dispatch builtin tools (same shapes as legacy Java*Tool). */
export const computeToolInputSchema = z.object({
  operation: z
    .enum(COMPUTE_OPERATIONS)
    .describe(
      "add|subtract|multiply|divide: two numbers in operands. factorial|square|sqrt: one number. timestamp_to_date: one Unix timestamp (seconds or ms). date_diff_days: two calendar dates as YYYY-MM-DD strings.",
    ),
  operands: z
    .array(z.union([z.number(), z.string()]))
    .min(1)
    .describe(
      "add: [3,5]. subtract|multiply|divide: [a,b]. factorial|square|sqrt: [n]. timestamp_to_date: [unixTs]. date_diff_days: [\"2026-03-08\",\"2026-03-12\"].",
    ),
});

const serverLookupToolInputSchema = z.object({
  serverName: z
    .string()
    .min(1)
    .describe("User-visible server name to search; returns up to 5 candidate serverId values (no credentials)."),
});


export const apiCallerToolInputSchema = z.object({
  url: z.string().url().describe("Full target URL"),
  method: z
    .enum(["GET", "POST", "PUT", "DELETE", "PATCH"])
    .default("GET")
    .describe("HTTP method"),
  headers: z
    .record(z.string(), z.string())
    .optional()
    .describe("Additional headers (Authorization, Content-Type, etc.)"),
  body: z
    .any()
    .optional()
    .describe("Request body (object, string, or null)"),
});

import {
  emitToolTraceEvent,
  getActiveParentToolId,
  sanitizeToolTraceArguments,
  sanitizeToolResultForTrace,
} from "./tool-trace-context";

export function formatToolError(error: unknown): string {
  if (axios.isAxiosError(error)) {
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
      } catch {
        return `${baseMessage}${statusPart}`;
      }
    }
    return `${baseMessage}${statusPart}`;
  }

  if (error instanceof Error) return error.message || error.name;
  if (typeof error === 'string') return error;
  try {
    return JSON.stringify(error);
  } catch {
    return String(error);
  }
}

export interface GatewaySkill {
  id: number;
  name: string;
  description?: string;
  type?: string;
  executionMode?: string;
  configuration?: string;
  enabled?: boolean;
  requiresConfirmation?: boolean;
  visibility?: string;
  createdBy?: string;
  /** Display emoji; persisted by Skill Gateway */
  avatar?: string;
  /** Template placeholders extracted by Gateway from prompt */
   templatePlaceholders?: string[];
   /** Unified schema properties computed by Gateway (replaces parameterContract parsing) */
   schemaProperties?: Record<string, { type: string; description?: string; default?: unknown; enum?: (string | number)[]; const?: unknown }>;
 }

export interface SkillMutationPayload {
  name: string;
  description: string;
  type: "EXTENSION";
  executionMode?: "CONFIG" | "OPENCLAW";
  configuration: string;
  enabled: boolean;
  requiresConfirmation: boolean;
  visibility?: "PUBLIC" | "PRIVATE";
  avatar?: string;
}

export interface ExtendedSkillConfig {
  kind?: string;
  preset?: string;
  profile?: string;
  operation?: string;
  lookup?: string;
  executor?: string;
  method?: string;
  endpoint?: string;
  command?: string;
  systemPrompt?: string;
  inputGuidance?: string;
  allowedTools?: string[];
  orchestration?: {
    mode?: string;
  };
  prompt?: string;
  headers?: Record<string, string>;
  query?: Record<string, string | number | boolean>;
  /** HTTP 超时秒数，默认 30；同时作用于 Agent Core → Gateway 和 Gateway → 外部 API 两段 */
  timeoutSeconds?: number;
  /** 异步轮询配置，存在时走异步路径 */
  asyncPoll?: AsyncPollConfig;
  /**
   * How merged scalar contract fields map to the outbound HTTP call (after `parameterContract` validation).
   * - `query` (default): append scalars to URL query; `merged.body` alone is the proxy body (legacy).
   * - `jsonBody`: send scalars as JSON object in the proxy body (POST/PUT/PATCH/DELETE); GET/HEAD falls back to `query`.
   * - `formBody`: send flat scalars as `application/x-www-form-urlencoded` body; GET/HEAD falls back to `query`.
   */
  parameterBinding?: "query" | "jsonBody" | "formBody";
  interfaceDescription?: string;
  parameterContract?: {
    type: "object";
    properties: Record<string, {
      type: string;
      description?: string;
      required?: boolean;
      enum?: string[];
      default?: any;
    }>;
    required?: string[];
  };
}

export interface AsyncPollConfig {
  /** 轮询端点模板，{id} 会被替换为外部任务 ID */
  pollEndpoint: string;
  /** 从初始响应中提取任务 ID 的 JSON 路径，如 "data.task_id" */
  idJsonPath?: string;
  /** 轮询 HTTP method，默认 GET */
  pollMethod?: string;
  /** 轮询间隔（毫秒），默认 5000 */
  pollIntervalMs?: number;
  /** 轮询间隔（秒），优先于 pollIntervalMs */
  pollIntervalSeconds?: number;
  /** 最大等待时间（毫秒），默认 600000（10分钟） */
  maxWaitMs?: number;
  /** 最大等待时间（秒），优先于 maxWaitMs */
  maxWaitSeconds?: number;
  /** 判断完成的 JSON 路径 */
  completionJsonPath?: string;
  /** 完成时的字段值 */
  completionValue?: string;
  /** 失败状态的字段值列表 */
  failedValues?: string[];
  /** 结果提取的 JSON 路径 */
  resultJsonPath?: string;
  /** 轮询请求头 */
  pollHeaders?: Record<string, string>;
}

export function readPreset(config: ExtendedSkillConfig): string | undefined {
  const value = config.preset ?? config.profile;
  if (typeof value !== "string") return undefined;
  const normalized = value.trim();
  return normalized || undefined;
}



const extendedOpenClawSkillToolSchema = z.object({
  input: z.string().optional().describe("User goal or parameters for the OPENCLAW planner."),
});

const extendedPassthroughSkillToolSchema = z.object({}).passthrough();

const extendedSkillConfirmationField = z.object({
  confirmed: z
    .boolean()
    .optional()
    .describe("Set by the confirmation UI when resuming; omit for normal calls."),
});

function withOptionalConfirmationFlag(schema: z.ZodTypeAny): z.ZodTypeAny {
  if (schema instanceof z.ZodObject) {
    return schema.merge(extendedSkillConfirmationField);
  }
  return z.intersection(schema, extendedSkillConfirmationField);
}

/**
 * 根据 Extension Skill 配置构建 Zod schema。
 *
 * schema 来源：Gateway 返回的 `schemaProperties`，而非 Agent 侧自行解析 `parameterContract`。
 * - `schemaProps` 由 Gateway `Skill.java` 的 `computeSchemaProperties()` 生成
 * - OPENCLAW Skill 使用固定的 `extendedOpenClawSkillToolSchema`（单一 input 字符串）
 * - 无 `schemaProps` 时回退到 `extendedPassthroughSkillToolSchema`（透传任意参数）
 */
function buildSkillZodSchema(
  config: ExtendedSkillConfig,
  schemaProps?: Record<string, { type: string; description?: string; default?: unknown; enum?: (string | number | { label: string; value: string | number })[]; const?: unknown; required?: boolean }>,
): z.ZodTypeAny {
  const executionMode = (config as { orchestration?: { mode?: string } }).orchestration?.mode;
  if (executionMode === "OPENCLAW" || (config.kind || "").toLowerCase() === "openclaw") {
    return withOptionalConfirmationFlag(extendedOpenClawSkillToolSchema);
  }

  if (!schemaProps || Object.keys(schemaProps).length === 0) {
    return withOptionalConfirmationFlag(extendedPassthroughSkillToolSchema);
  }

  const shape: Record<string, z.ZodTypeAny> = {};
  for (const [key, prop] of Object.entries(schemaProps)) {
    const desc = prop.description || key;
    const hasConst = "const" in prop;
    const hasDefault = "default" in prop;
    // required logic: explicit required flag takes precedence
    // if not specified, fields with default/const are optional, others are optional too (backward compatible)
    const isRequired = prop.required === true;

    // Normalize enum: [{label, value}] → [value, ...]
    const rawEnum = prop.enum;
    const enumValues = Array.isArray(rawEnum)
      ? rawEnum.map((v) => (typeof v === "object" && v !== null && "value" in v) ? v.value : v)
      : undefined;

    let descWithMeta = desc;
    if (hasConst) descWithMeta += " (fixed value, omit)";
    // enum and default are already in JSON Schema fields, no need to repeat in description

    let field: z.ZodTypeAny;
    if (prop.type === "number" || prop.type === "integer") {
      let baseField: z.ZodTypeAny;
      if (enumValues && enumValues.length > 0) {
        const enumStrs = enumValues.map(String);
        baseField = z.enum([enumStrs[0], ...enumStrs.slice(1)]).transform(Number);
      } else {
        baseField = z.number();
      }
      if (hasDefault) {
        field = baseField.default(prop.default);
      } else if (isRequired) {
        field = baseField;
      } else {
        field = baseField.optional();
      }
    } else if (prop.type === "boolean") {
      let baseField = z.boolean();
      if (hasDefault) {
        field = baseField.default(prop.default as boolean);
      } else if (isRequired) {
        field = baseField;
      } else {
        field = baseField.optional();
      }
    } else {
      let baseField: z.ZodTypeAny;
      if (enumValues && enumValues.length > 0) {
        const enumStrs = enumValues.map(String);
        baseField = z.enum([enumStrs[0], ...enumStrs.slice(1)]);
      } else {
        baseField = z.string();
      }
      if (hasDefault) {
        field = baseField.default(prop.default);
      } else if (isRequired) {
        field = baseField;
      } else {
        field = baseField.optional();
      }
    }
    shape[key] = field.describe(descWithMeta);
  }

  return withOptionalConfirmationFlag(z.object(shape).passthrough());
}

interface GatewayToolMetadata {
  displayName: string;
  executionMode?: string;
  executionLabel?: string;
}

const gatewayExtendedToolRegistry = new Map<string, GatewayToolMetadata>();
const gatewayExtendedToolIdRegistry = new Map<number, GatewayToolMetadata>();

function normalizeToolName(name: string, id: number): string {
  let processedName = name;
  if (/[\u4e00-\u9fff]/.test(name)) {
    try {
      processedName = pinyin(name, { toneType: "none", type: "array" }).join(" ");
    } catch {
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

function normalizeExecutionMode(executionMode?: string): "CONFIG" | "OPENCLAW" {
  return executionMode?.toUpperCase() === "OPENCLAW" ? "OPENCLAW" : "CONFIG";
}

function localizeExecutionMode(executionMode?: string): "预配置" | "自主规划" {
  return normalizeExecutionMode(executionMode) === "OPENCLAW" ? "自主规划" : "预配置";
}

function registerGatewayToolMetadata(toolName: string, skill: GatewaySkill) {
  const metadata: GatewayToolMetadata = {
    displayName: skill.name || `skill_${skill.id}`,
    executionMode: normalizeExecutionMode(skill.executionMode),
    executionLabel: localizeExecutionMode(skill.executionMode),
  };

  gatewayExtendedToolRegistry.set(toolName, metadata);
  gatewayExtendedToolRegistry.set(toolName.replace(/_/g, "-"), metadata);
  gatewayExtendedToolIdRegistry.set(skill.id, metadata);
}

export function describeGatewayExtendedTool(toolName: string): { displayName: string; kind: 'skill' | 'tool'; executionMode?: string; executionLabel?: string } | null {
  const metadata =
    gatewayExtendedToolRegistry.get(toolName)
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

  // Fallback: resolve by trailing numeric id (e.g. extended-skill-1 / extended_skill_1)
  const idMatch = toolName.match(/(\d+)$/);
  if (!idMatch) return null;
  const skillId = Number(idMatch[1]);
  if (!Number.isFinite(skillId)) return null;
  const idDisplayName = gatewayExtendedToolIdRegistry.get(skillId);
  if (!idDisplayName) return null;
  return {
    displayName: idDisplayName.displayName,
    kind: 'skill',
    executionMode: idDisplayName.executionMode,
    executionLabel: idDisplayName.executionLabel,
  };
}

export function normalizeParameterBindingValue(raw: unknown): "query" | "jsonBody" | "formBody" | undefined {
  if (raw === "jsonBody" || raw === "query" || raw === "formBody") return raw;
  return undefined;
}

export function normalizeExtendedConfig(cfg: ExtendedSkillConfig): ExtendedSkillConfig {
  const next: ExtendedSkillConfig = { ...cfg };
  const pb = normalizeParameterBindingValue((cfg as { parameterBinding?: unknown }).parameterBinding);
  if (pb) {
    next.parameterBinding = pb;
  } else {
    delete (next as { parameterBinding?: unknown }).parameterBinding;
  }
  const rawPc = (cfg as { parameterContract?: unknown }).parameterContract;
  if (typeof rawPc === "string" && rawPc.trim()) {
    try {
      const parsed = JSON.parse(rawPc) as ExtendedSkillConfig["parameterContract"];
      if (parsed && typeof parsed === "object") {
        next.parameterContract = parsed as ExtendedSkillConfig["parameterContract"];
      }
    } catch {
      // keep original
    }
  }
  return next;
}

export function parseSkillConfig(skill: GatewaySkill): ExtendedSkillConfig {
  if (!skill.configuration || !skill.configuration.trim()) return {};
  try {
    const parsed = JSON.parse(skill.configuration);
    if (!parsed || typeof parsed !== "object") return {};
    return normalizeExtendedConfig(parsed as ExtendedSkillConfig);
  } catch {
    return {};
  }
}

export function normalizeParameterContractRequired(contract: Record<string, unknown>): Record<string, unknown> {
  const out = { ...contract };
  const props = out.properties as Record<string, Record<string, unknown>> | undefined;
  if (!props) return out;

  const existingRequired = Array.isArray(out.required) ? (out.required as string[]) : [];
  const requiredSet = new Set(existingRequired);
  const normalizedProps: Record<string, Record<string, unknown>> = {};

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
  } else {
    delete out.required;
  }
  return out;
}

export function normalizeGeneratedOperation(value: string): string {
  const normalized = value
    .trim()
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, "_")
    .replace(/^_+|_+$/g, "");

  return normalized || "api_request";
}


export function sanitizeConfigForDisplay(config: ExtendedSkillConfig): ExtendedSkillConfig {
  return config;
}

export type BindableAgentTool = Tool | DynamicTool | StructuredTool;


async function executeOpenClawSkill(
  plannerModel: any,
  parentToolName: string,
  input: string,
  config: ExtendedSkillConfig,
  availableTools: BindableAgentTool[],
): Promise<string> {
  const orchestrationMode = config.orchestration?.mode || "serial";
  if (orchestrationMode !== "serial") {
    return JSON.stringify({ error: `Unsupported OPENCLAW orchestration mode: ${orchestrationMode}` });
  }

  const availableToolLookup = new Map<string, BindableAgentTool>();
  availableTools.forEach((tool) => {
    availableToolLookup.set(tool.name, tool);
    availableToolLookup.set(tool.name.replace(/_/g, "-"), tool);
    availableToolLookup.set(tool.name.replace(/-/g, "_"), tool);
    const metadata = describeGatewayExtendedTool(tool.name);
    if (metadata?.displayName) {
      availableToolLookup.set(metadata.displayName, tool);
    }
  });

  const allowedTools = resolveAllowedTools(config.allowedTools, availableToolLookup);
  const missingTools = (config.allowedTools || []).filter((name) => (
    !availableToolLookup.get(name)
    && !availableToolLookup.get(name.trim())
    && !availableToolLookup.get(name.replace(/-/g, "_"))
  ));
  if (missingTools.length > 0) {
    return JSON.stringify({ error: `OPENCLAW skill is missing required tools: ${missingTools.join(", ")}` });
  }

  const parentToolId = getActiveParentToolId(parentToolName);
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

  const messages: any[] = [
    { role: "system", content: systemPrompt },
    { role: "user", content: input || "{}" },
  ];

  for (let round = 0; round < 6; round += 1) {
    const response = await planner.invoke(messages);
    const rawToolCalls = Array.isArray((response as any)?.tool_calls) ? (response as any).tool_calls : [];

    let toolCalls = rawToolCalls;
    if (orchestrationMode === "serial" && rawToolCalls.length > 1) {
      toolCalls = [rawToolCalls[0]];
      messages.push(
        new AIMessage({
          content: (response as AIMessage).content ?? "",
          tool_calls: toolCalls as any,
        }),
      );
    } else {
      messages.push(response);
    }

    if (toolCalls.length === 0) {
      const content = (response as any)?.content;
      if (typeof content === "string") return content;
      if (Array.isArray(content)) {
        return content
          .map((part: any) => typeof part === "string" ? part : part?.text || "")
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
      emitToolTraceEvent({
        type: "tool_status",
        toolId: childToolId,
        toolName: tool.name,
        displayName: childDisplayName,
        kind: describeGatewayExtendedTool(tool.name)?.kind || "tool",
        status: "running",
        parentToolId,
        parentToolName,
        arguments: sanitizeToolTraceArguments(toolCall.args || {}),
      });

      try {
        const result = await invokeToolDirect(tool, toolCall.args || {});
        emitToolTraceEvent({
          type: "tool_status",
          toolId: childToolId,
          toolName: tool.name,
          displayName: childDisplayName,
          kind: describeGatewayExtendedTool(tool.name)?.kind || "tool",
          status: "completed",
          parentToolId,
          parentToolName,
          summary: summarizeToolResult(result),
          result: sanitizeToolResultForTrace(result),
        });
        const resolvedCallId =
          typeof toolCall.id === "string" && toolCall.id.trim() ? toolCall.id : childToolId;
        messages.push({
          role: "tool",
          tool_call_id: resolvedCallId,
          content: result,
        });
      } catch (error) {
        if (isGraphInterrupt(error)) throw error;
        const message = formatToolError(error);
        emitToolTraceEvent({
          type: "tool_status",
          toolId: childToolId,
          toolName: tool.name,
          displayName: childDisplayName,
          kind: describeGatewayExtendedTool(tool.name)?.kind || "tool",
          status: "failed",
          parentToolId,
          parentToolName,
          summary: message,
          result: sanitizeToolResultForTrace(message),
        });
        const resolvedCallId =
          typeof toolCall.id === "string" && toolCall.id.trim() ? toolCall.id : childToolId;
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

export function gatewaySkillMutationHeaders(apiToken: string, userId?: string, sessionId?: string): Record<string, string> {
  const headers: Record<string, string> = {
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

export function gatewaySkillReadHeaders(apiToken: string, userId?: string): Record<string, string> {
  const headers: Record<string, string> = {
    "X-Agent-Token": apiToken,
  };
  if (userId && String(userId).trim()) {
    headers["X-User-Id"] = String(userId).trim();
  }
  return headers;
}



/**
 * 从 Gateway 动态加载启用的 Extension Skill 并注册为 LangChain StructuredTool。
 *
 * 架构要点：
 * - 所有扩展 Skill（API/SSH/Template）统一通过 `POST /api/skills/execute` 执行
 * - Skill 的 Zod schema 由 Gateway 返回的 `schemaProperties` 驱动（见 `buildSkillZodSchema`）
 * - OPENCLAW Skill 由 `executeOpenClawSkill` 处理子规划流程
 * - 确认机制通过 LangGraph `interrupt` 实现，高风险操作需用户确认
 *
 * @returns 注册为 `extended_<name>` 的 StructuredTool 数组
 */
export async function loadGatewayExtendedTools(
  gatewayUrl: string,
  apiToken: string,
  userId?: string,
  options?: {
    plannerModel?: any;
    /** Base agent tools (including structured tools such as compute). */
    availableTools?: BindableAgentTool[];
    sessionId?: string;
  },
): Promise<StructuredTool[]> {
  try {
    const listHeaders = gatewaySkillReadHeaders(apiToken, userId);
    const response = await axios.get(`${gatewayUrl}/api/skills`, {
      headers: listHeaders,
    });

    const skills = Array.isArray(response.data) ? response.data as GatewaySkill[] : [];
    const extensionSkills = skills.filter(
      (skill) => skill.enabled && (skill.type || "").toUpperCase() === "EXTENSION"
    );
    const toolLookup = new Map<string, BindableAgentTool>();
    (options?.availableTools || []).forEach((tool) => {
      toolLookup.set(tool.name, tool);
    });

    const resolvedTools: StructuredTool[] = [];
    for (const skill of extensionSkills) {
      console.log(`[DEBUG] skill ${skill.id} configuration:`, skill.configuration);
      let workingSkill = skill;
      let config = skill.configuration ? parseSkillConfig(skill) : {} as ExtendedSkillConfig;
      if (!skill.configuration?.trim()) {
        try {
          const detailResponse = await axios.get(`${gatewayUrl}/api/skills/${skill.id}`, {
            headers: gatewaySkillReadHeaders(apiToken, userId),
          });
          workingSkill = detailResponse.data as GatewaySkill;
          config = parseSkillConfig(workingSkill);
        } catch {
          /* keep empty config; tool may still error at runtime */
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
      const structuredTool = new DynamicStructuredTool({
        name: toolName,
        description: toolDescription,
        schema: zodSchema,
        func: async (args: Record<string, unknown>, _runManager?: unknown, runConfig?: RunnableConfig) => {
          try {
            let execInput: unknown = args;
            let currentSkill = workingSkill;
            let currentConfig = config;

            // Always fetch latest config from Gateway so edits take effect immediately.
            try {
              const detailResponse = await axios.get(`${gatewayUrl}/api/skills/${skill.id}`, {
                headers: gatewaySkillReadHeaders(apiToken, userId),
              });
              currentSkill = detailResponse.data as GatewaySkill;
              currentConfig = parseSkillConfig(currentSkill);
            } catch {
              // Gateway unavailable; fall back to cached config
            }

            const executionMode = normalizeExecutionMode(currentSkill.executionMode);

            // OPENCLAW stays separate — sub-planning needs LLM
            if (executionMode === "OPENCLAW" || (currentConfig.kind || "").toLowerCase() === "openclaw") {
              const openClawInput =
                typeof execInput === "string"
                  ? execInput
                  : execInput && typeof execInput === "object" && typeof (execInput as Record<string, unknown>).input === "string"
                    ? String((execInput as Record<string, unknown>).input)
                    : JSON.stringify(execInput ?? {});
              return await executeOpenClawSkill(
                options?.plannerModel,
                toolName,
                openClawInput,
                currentConfig,
                Array.from(toolLookup.values()),
              );
            }

            // All CONFIG skills → unified Gateway execute endpoint
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
              executeResponse = await axios.post(executeUrl, executePayload, { headers: executeHeaders });
            } catch (apiError) {
              return `Error executing extended skill "${skill.name}": ${formatToolError(apiError)}`;
            }

            const responseData = executeResponse.data as { status?: string; requestId?: string; [key: string]: unknown };

            // Gateway handles confirmation — if CONFIRMATION_REQUIRED, interrupt and wait
            if (responseData.status === "CONFIRMATION_REQUIRED") {
              const toolCallId = runConfig?.configurable?.thread_id
                ? `${String(runConfig.configurable.thread_id)}:${toolName}`
                : `${toolName}:confirm`;

              const resume = interrupt<
                {
                  kind: "extended_skill_confirmation";
                  toolName: string;
                  toolCallId: string;
                  skillName: string;
                  skillId: number;
                  summary: string;
                  details: string;
                  parametersPreview: unknown;
                  gatewayRequestId: string;
                },
                { confirmed: boolean; adjustedParams?: Record<string, unknown> }
              >({
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

              const result = resume as { confirmed: boolean; adjustedParams?: Record<string, unknown> };
              if (!result.confirmed) {
                return JSON.stringify({ status: "CANCELLED", message: "User cancelled the skill execution." });
              }

              // Re-call Gateway with confirmed flag
              const confirmedPayload = {
                skillId: currentSkill.id,
                parameters: parameters,
                confirmed: true,
                requestId: responseData.requestId,
                ...(result.adjustedParams ? { adjustedParams: result.adjustedParams } : {}),
              };
              let confirmedResponse;
              try {
                confirmedResponse = await axios.post(executeUrl, confirmedPayload, { headers: executeHeaders });
              } catch (confirmedError) {
                return `Error executing extended skill "${skill.name}" after confirmation: ${formatToolError(confirmedError)}`;
              }
              return typeof confirmedResponse.data === "string"
                ? confirmedResponse.data
                : JSON.stringify(confirmedResponse.data);
            }

            return typeof executeResponse.data === "string"
              ? executeResponse.data
              : JSON.stringify(executeResponse.data);
          } catch (error) {
            if (isGraphInterrupt(error)) throw error;
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
  } catch (error) {
    console.error("[agent-core] Failed to load extended skills from gateway:", formatToolError(error));
    return [];
  }
}

/**
 * Build JSON string for legacy DynamicTool input with `confirmed: true` merged with prior tool args.
 */
export function buildConfirmedToolInputString(args: unknown): string {
  return JSON.stringify(buildConfirmedToolArgs(args));
}

/** Merge prior tool args with `confirmed: true` for structured extended skills (confirmation resume). */
export function buildConfirmedToolArgs(args: unknown): Record<string, unknown> {
  if (args === undefined || args === null) {
    return { confirmed: true };
  }
  if (typeof args === "object" && !Array.isArray(args)) {
    return { ...(args as Record<string, unknown>), confirmed: true };
  }
  if (typeof args === "string") {
    const trimmed = args.trim();
    if (!trimmed) return { confirmed: true };
    try {
      const o = JSON.parse(trimmed) as unknown;
      if (o && typeof o === "object" && !Array.isArray(o)) {
        return { ...(o as Record<string, unknown>), confirmed: true };
      }
    } catch {
      return { confirmed: true, input: trimmed };
    }
    return { confirmed: true, input: trimmed };
  }
  return { confirmed: true, input: String(args) };
}

/**
 * Re-run an extended skill tool with the same arguments plus `confirmed: true` (no LLM).
 */
export async function invokeExtendedSkillWithConfirmed(
  gatewayUrl: string,
  apiToken: string,
  userId: string | undefined,
  toolName: string,
  toolArguments: unknown,
  options: {
    plannerModel: any;
    availableTools: BindableAgentTool[];
  },
): Promise<string> {
  const extendedTools = await loadGatewayExtendedTools(gatewayUrl, apiToken, userId, {
    plannerModel: options.plannerModel,
    availableTools: options.availableTools,
  });
  const underscore = toolName.replace(/-/g, "_");
  const tool =
    extendedTools.find((t) => t.name === toolName)
    ?? extendedTools.find((t) => t.name === underscore);
  if (!tool) {
    return JSON.stringify({ error: `Extended skill tool not found: ${toolName}` });
  }
  const merged = buildConfirmedToolArgs(toolArguments);
  const raw = await (tool as DynamicStructuredTool).invoke(merged);
  return typeof raw === "string" ? raw : JSON.stringify(raw);
}


/**
 * Java 计算工具（built-in 名：`compute`）。
 *
 * 封装对 Java Skill Gateway 计算接口的调用，支持数学运算（加减乘除、阶乘等）和日期计算。
 * 使用 DynamicStructuredTool + Zod 暴露 operation/operands 结构化入参。
 *
 * 路由：通过 `AGENT_BUILTIN_SKILL_DISPATCH` 控制——`legacy` 直连 `/api/skills/compute`，
 * `gateway` 走 `/api/system-skills/execute` 统一入口。
 */
export class JavaComputeTool extends DynamicStructuredTool<typeof computeToolInputSchema> {
  constructor(gatewayUrl: string, apiToken: string, options?: { dispatch?: BuiltinSkillDispatch }) {
    const dispatch: BuiltinSkillDispatch = options?.dispatch ?? "legacy";
    const baseUrl = gatewayUrl.replace(/\/+$/, "");
    super({
      name: "compute",
      description:
        "Math and date operations via Skill Gateway. Supply operation and operands as separate fields (see parameter schema). "
        + "Gateway body is { operation, operands }—do not wrap them in an extra input string.",
      schema: computeToolInputSchema,
      func: async (args) => {
        try {
          const headers = {
            "X-Agent-Token": apiToken,
            "Content-Type": "application/json",
          };
          const payload =
            dispatch === "gateway"
              ? { toolName: "compute", arguments: { operation: args.operation, operands: args.operands } }
              : { operation: args.operation, operands: args.operands };
          const url =
            dispatch === "gateway"
              ? `${baseUrl}/api/system-skills/execute`
              : `${gatewayUrl}/api/skills/compute`;
          const response = await axios.post(url, payload, { headers });
          return JSON.stringify(response.data);
        } catch (error) {
          return `Error executing compute: ${formatToolError(error)}`;
        }
      },
    });
  }
}

/**
 * Java 服务器查询工具（built-in 名：`server_lookup`）。
 *
 * 通过 Gateway 查询用户台账中的服务器列表，按 serverName 模糊匹配，返回最多 5 条候选（id + name）。
 * 查询结果供 SSH Extension Skill 使用——用户选定服务器 id 后，由 SSH Extension Skill（kind: "ssh"）
 * 通过 `POST /api/skills/execute` 执行命令。
 *
 * 连接凭证仅存储在 Gateway 数据库，Agent 侧不接触。
 */
export class JavaServerLookupTool extends DynamicStructuredTool<typeof serverLookupToolInputSchema> {
  constructor(gatewayUrl: string, apiToken: string, userId?: string) {
    super({
      name: "server_lookup",
      description:
        "Finds up to 5 server candidates (`id` + `name`) for a user-entered serverName (relevance-ordered; connection secrets stay in Gateway DB only, not returned). " +
        "If exactly one row, use its `id` for linux_script_executor next; if several, ask the user to pick an `id`. " +
        "Provide `serverName` (do NOT wrap in a single input string).",
      schema: serverLookupToolInputSchema,
      func: async (args) => {
        try {
          const headers: Record<string, string> = {
            "X-Agent-Token": apiToken,
            "Content-Type": "application/json",
          };
          if (userId) {
            headers["X-User-Id"] = userId;
          }

          const response = await axios.get(
            `${gatewayUrl}/api/skills/server-lookup`,
            {
              headers,
              params: { serverName: args.serverName },
            }
          );
          return JSON.stringify(response.data);
        } catch (error) {
          return `Error looking up server: ${formatToolError(error)}`;
        }
      },
    });
  }
}

/**
 * Java API 工具（built-in 名：`api_caller`）。
 *
 * **当前生产默认不在 `AgentFactory` 中挂载**（见 `agent.ts`），避免与「仅通过扩展 API Skill
 * 出站」的产品策略重叠。扩展 API Skill 的执行路径是 `POST /api/skills/execute` →
 * Gateway `ApiProxyService`，与该类无嵌套调用关系。
 *
 * `AGENT_BUILTIN_SKILL_DISPATCH` 仅当本工具**被注册**时，影响其出站到 Gateway 的 URL
 *（`legacy`：`/api/skills/api`；`gateway`：`/api/system-skills/execute` + `toolName: api_caller`）。
 *
 * 英文说明见 `JAVA_API_TOOL_DESCRIPTION`。
 */
const JAVA_API_TOOL_DESCRIPTION =
  "Calls an external API via the Java gateway. " +
  "Provide url, method, headers, and body as separate fields (do NOT wrap everything in a single JSON string under 'input'). " +
  "If an extension skill covers the same HTTP capability, use that extension tool instead of this built-in.";

export class JavaApiTool extends DynamicStructuredTool<typeof apiCallerToolInputSchema> {
  constructor(gatewayUrl: string, apiToken: string, options?: { dispatch?: BuiltinSkillDispatch }) {
    const dispatch: BuiltinSkillDispatch = options?.dispatch ?? "legacy";
    const baseUrl = gatewayUrl.replace(/\/+$/, "");
    super({
      name: "api_caller",
      description: JAVA_API_TOOL_DESCRIPTION,
      schema: apiCallerToolInputSchema,
      func: async (args) => {
        try {
          const headers = {
            "X-Agent-Token": apiToken,
            "Content-Type": "application/json",
          };
          const url =
            dispatch === "gateway"
              ? `${baseUrl}/api/system-skills/execute`
              : `${gatewayUrl}/api/skills/api`;
          const body = dispatch === "gateway" ? { toolName: "api_caller", arguments: args } : args;
          const response = await axios.post(url, body, { headers });
          return JSON.stringify(response.data);
        } catch (error) {
          return `Error calling API: ${formatToolError(error)}`;
        }
      },
    });
  }
}
