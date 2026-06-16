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
import { AsyncLocalStorage } from "async_hooks";
import { tryParseJson, invokeToolDirect, summarizeToolResult, resolveAllowedTools } from "./openclaw-executor";

/**
 * Bxdcbot 自规划 mode 调用上下文（AsyncLocalStorage）。
 *
 * 当 executeOpenClawSkill / runSubPlanner 调 gateway skill 时设置 true，
 * 让 func 知道"不要阻塞等 async 真结果"——async placeholder 留给 BxdcbotRunScheduler 处理。
 *
 * Chat mode（LangGraph agent 直接调 tool）走 func 时不在这个上下文里，
 * 默认阻塞等真结果，让 LLM 看到真结果再继续规划。
 */
export const bxdcbotPlannerCallContext = new AsyncLocalStorage<boolean>();
import { globalBxdcbotRunStore, BxdcbotRun, BxdcbotPlannerContext } from "../services/bxdcbot-run-store";
import { notifyBxdcbotRunComplete } from "../services/bxdcbot-run-notifier";

/**
 * Bxdcbot 子技能调用时的运行时上下文（AsyncLocalStorage）。
 * 替代模块级全局变量 activeBxdcbotContext，避免并发请求间状态串扰。
 *
 * 在 runSubPlanner 的 tool 调用前设置，在 func 闭包中读取。
 * 用于将 parentToolId/parentSkillId 注入到子 skill 的 execute payload 中，
 * 让 gateway 的 async_tasks 表正确记录父子关系。
 */
interface BxdcbotActiveRunCtx {
  parentToolId: string
  parentSkillId: number
  conversationId: string
  userId: string
}
const bxdcbotActiveRunStorage = new AsyncLocalStorage<BxdcbotActiveRunCtx>();

/** @deprecated 用 bxdcbotActiveRunStorage.getStore() 替代模块级全局变量 */
export function getActiveBxdcbotContext(): BxdcbotActiveRunCtx | undefined {
  return bxdcbotActiveRunStorage.getStore();
}

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
  /** 轮询端点模板，{id} 会被替换为外部任务 ID。SINGLE_CALL 模式下可省略（fallback 到请求 URL）。 */
  pollEndpoint?: string;
  /** 从初始响应中提取任务 ID 的 JSON 路径，如 "data.task_id"。SINGLE_CALL 模式下不需要。 */
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
  /**
   * 轮询策略：
   * - 'PERIODIC'（默认）：周期轮询，需要 pollEndpoint 包含 {id} 占位符。
   * - 'SINGLE_CALL'：单次长调用（无 pollEndpoint 也可以，靠长 readTimeout 等结果）；
   *                 提交后立即返回 asyncTaskId，LLM 异步获知结果。
   */
  pollStrategy?: "PERIODIC" | "SINGLE_CALL";
  /** SINGLE_CALL 模式专用 read timeout（秒）。未设置时回退到 maxWaitSeconds。 */
  singleCallReadTimeoutSeconds?: number;
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

/**
 * 包装 zod schema 以保证 JSON Schema 顶层一定有 `type: "object"`，兼容 DeepSeek 严格校验。
 * 部分 zod schema（如 .passthrough()）在序列化为 JSON Schema 时
 * 不会自动加 type: "object"，DeepSeek 会返回 400 错误。
 */
function ensureObjectType<T extends z.ZodTypeAny>(inner: T, description: string): z.ZodType<{ payload?: unknown }> {
  return z.object({ payload: inner.optional().describe(description) }).passthrough() as any;
}

const extendedPassthroughSkillToolSchema = ensureObjectType(
  z.object({}).passthrough(),
  "Any parameters passed through as-is"
);

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


/**
 * executeOpenClawSkill —— bxdcbot-multi-turn-async 多周期改造版
 *
 * 关键设计（决策 1-11 + 漏洞 1-3 修复）：
 * - 第一周期：创建 BxdcbotRun + 跑 6 轮 LLM 子规划
 * - 调 async skill 时：检测到 {asyncTaskId, status: "POLLING"|"SINGLE_CALLED"} → 立即退出循环
 *   run.status 置 awaiting_async + 记录 pendingAsyncTaskIds + 记录 asyncTaskIdToToolCallId（漏洞 3）
 * - run.awaiting_async 状态由 BxdcbotRunScheduler 监听 async 完成
 * - 全完成时 resumer 重新进入新周期（调 resumeBxdcbotPlanner）
 * - 60 轮触顶：run.status 置 failed + 调 complete 回灌
 * - 决策 11 失败隔离：失败 MUST NOT 注入 messages；走 scheduler 重试路径
 * - 决策 8/9：system prompt 加 1 行"async 占位不要调下游"约束
 *
 * 关键设计（用户硬性要求"1-10 个 async 统一处理"）：
 * - N=1 / N=5-10 走**同一套**代码路径：pendingAsyncTaskIds Set、asyncTaskIdToToolCallId Map、注入 messages
 * - scheduler 处理时**不感知** size 是 1 还是 10（用户要求）
 */
async function executeOpenClawSkill(
  plannerModel: any,
  parentToolName: string,
  input: string,
  config: ExtendedSkillConfig,
  availableTools: BindableAgentTool[],
): Promise<string> {
  // Bxdcbot 自规划 mode 上下文（让嵌套的工具调用 func 不阻塞等 async 结果）
  return await bxdcbotPlannerCallContext.run(true, async () => {
    return await executeOpenClawSkillImpl(plannerModel, parentToolName, input, config, availableTools);
  });
}

async function executeOpenClawSkillImpl(
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

  // 解析 parentSkillId（从 Bxdcbot tool 的 skill_id 入参；这里 fallback 用 0）
  const parentSkillId = (config as any)?.skill_id ?? (config as any)?.skillId ?? 0;
  // 解析 conversationId / userId（从 active context 取）
  const conversationId = (config as any)?.conversationId ?? (config as any)?.sessionId ?? "unknown";
  const userId = (config as any)?.userId ?? (config as any)?.user_id ?? "unknown";
  // 解析主 skill 展示名（来自 config.displayName / config.skill_name / describeGatewayExtendedTool metadata）
  const parentMetadata = describeGatewayExtendedTool(parentToolName);
  const parentSkillName = (config as any)?.displayName
    || (config as any)?.skill_name
    || (config as any)?.skillName
    || parentMetadata?.displayName
    || parentToolName;

  // 创建 BxdcbotRun（决策 2：状态机 + 跨周期上下文）
  const run = globalBxdcbotRunStore.create({
    conversationId,
    userId,
    parentToolId,
    parentSkillId,
    parentSkillName,
    plannerContext: { plannerModel, parentToolName, input, config, availableTools },
  });

  // 跑子规划（第一周期）
  const result = await runSubPlanner(run, planner, parentToolName, input, config, availableTools, allowedTools, parentToolId);

  // 第一周期直接完成（纯 sync）→ 调 complete 回灌
  if (run.status === "completed" && run.result) {
    notifyBxdcbotRunComplete(run).catch((e) => console.warn("[executeOpenClawSkill] notifyBxdcbotRunComplete failed:", e));
  }
  if (run.status === "failed") {
    notifyBxdcbotRunComplete(run).catch((e) => console.warn("[executeOpenClawSkill] notifyBxdcbotRunComplete failed:", e));
  }
  return result;
}

/**
 * runSubPlanner —— 跑一个 6 轮子规划周期（被 executeOpenClawSkill 和 resumeBxdcbotPlanner 复用）。
 *
 * 行为：
 * - 把 run.messages 喂给 LLM（第一周期 system + user；第二周期开始累加）
 * - 6 轮循环：invoke LLM → if tool_calls → 执行 tool → push tool result
 * - 检测 async 调（结果含 asyncTaskId + status: POLLING/SINGLE_CALLED）→ 立即退出循环
 * - run.status 流转：running → awaiting_async / completed
 */
async function runSubPlanner(
  run: BxdcbotRun,
  planner: any,
  parentToolName: string,
  input: string,
  config: ExtendedSkillConfig,
  availableTools: BindableAgentTool[],
  allowedTools: BindableAgentTool[],
  parentToolId: string,
): Promise<string> {
  const orchestrationMode = config.orchestration?.mode || "serial";
  const systemPrompt = [
    config.systemPrompt || "You are an autonomous planning skill.",
    // 决策 13：自主规划子规划器要求「一次规划所有子任务，按顺序逐个 tool call」
    "PLANNING RULES:",
    "1) Analyze the user input ONCE and emit ALL needed tool_calls in a SINGLE response (one tool call per task, in the correct execution order).",
    "2) Do NOT stop, summarize, or wait for the user between tool calls. After each tool result, immediately call the NEXT tool you already planned.",
    "3) The runtime executes your tool_calls sequentially in the order you emit them. There is no extra round-trip between calls — the next tool starts as soon as the previous one returns.",
    "4) Only emit a final text response (no tool_calls) when ALL required sub-tasks are completed and you are ready to give the user a final answer.",
    "5) For the compute tool, use structured arguments: operation (enum) and operands (array)—see tool schema. Do not nest under an input key.",
    "6) If the user's input is genuinely ambiguous (cannot be parsed at all), ask ONE clarification question. Otherwise, plan and execute.",
    // bxdcbot-multi-turn-async 决策 9：1 行约束（让 LLM 第一眼看到）
    "\u26a0\ufe0f Async sub-skill returns {asyncTaskId, status: \"POLLING\"|\"SINGLE_CALLED\"} as a PLACEHOLDER. " +
    "Do NOT plan downstream sub-tasks that depend on the placeholder's result. If you need the real result, end the current turn and wait — the real result will be injected and the next planning cycle will resume.",
  ].join("\n\n");

  // 累加 messages：第一周期初始 system + user；后续周期 messages 在 run.messages 里
  let messages: any[];
  if (run.messages.length === 0) {
    messages = [
      { role: "system", content: systemPrompt },
      { role: "user", content: input || "{}" },
    ];
    console.log(`[runSubPlanner] runId=${run.runId} round=${run.currentRound} init: system + user`);
  } else {
    // 续周期：messages 在 run.messages 里（保持累积）
    messages = [...run.messages];
    console.log(`[runSubPlanner] runId=${run.runId} round=${run.currentRound} resume: messages.length=${messages.length}, roles=${JSON.stringify(messages.map((m: any) => m.role))}`);
  }

  let asyncDetected = false;

  for (let round = 0; round < 6; round += 1) {
    console.log(`[runSubPlanner] runId=${run.runId} invoking LLM round=${round}, messages=${messages.length}`);
    const response = await planner.invoke(messages);
    run.totalLlmCalls += 1;
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
      // run 跑完（completed）
      const finalText = (() => {
        if (typeof content === "string") return content;
        if (Array.isArray(content)) {
          return content.map((part: any) => typeof part === "string" ? part : part?.text || "").join("");
        }
        return JSON.stringify(content ?? "");
      })();

      run.status = "completed";
      run.result = finalText;
      run.finishedAt = new Date();
      run.messages = messages;
      console.log(`[runSubPlanner] runId=${run.runId} COMPLETED: finalText=${finalText.slice(0, 100)}`);
      return finalText;
    }

    const runCtx: BxdcbotActiveRunCtx = {
      parentToolId: run.parentToolId,
      parentSkillId: run.parentSkillId ?? 0,
      conversationId: run.conversationId,
      userId: run.userId,
    };

    // 在 AsyncLocalStorage 上下文中执行子技能调用（避免模块级全局变量并发串扰）
    let unauthorizedError: string | null = null;
    await bxdcbotActiveRunStorage.run(runCtx, async () => {
      for (const toolCall of toolCalls) {
      const tool = allowedTools.find((candidate) => candidate.name === toolCall.name);
      if (!tool) {
        unauthorizedError = JSON.stringify({ error: `OPENCLAW skill tried to call unauthorized tool: ${toolCall.name}` });
        return;
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

      let result: string;
      try {
        result = await invokeToolDirect(tool, toolCall.args || {});
      } catch (error) {
        if (isGraphInterrupt(error)) throw error;
        const message = formatToolError(error);
        // 决策 11：sync skill 失败也走"重试 + 失败隔离"路径
        // 但 sync skill 不能 asyncTaskId 关联，简化处理：注入 error tool result，让 LLM 看到错误
        // （注：sync skill 失败当前 NOT 走决策 11 自动重试——决策 11 主用于 async；sync 失败由 LLM 决定）
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
        continue;
      }

      // 检测 async skill 调用（决策 1 + 决策 3 + 漏洞 3 修复）
      let parsedResult: any = null;
      try { parsedResult = JSON.parse(result); } catch (_) {}
      if (parsedResult && typeof parsedResult === "object"
          && typeof parsedResult.asyncTaskId === "number"
          && (parsedResult.status === "POLLING" || parsedResult.status === "SINGLE_CALLED")) {
        // async skill 调完：记录到 BxdcbotRun
        const asyncTaskId = parsedResult.asyncTaskId;
        const args = toolCall.args || {};
        globalBxdcbotRunStore.registerAsyncTask(run.runId, tool.name, asyncTaskId, childToolId, args);
        // 追踪子技能结果（用于 BXDCBOT_RUN_RESULT 消息展示）
        run.subTaskResults.push({
          skillName: tool.name,
          status: "async_pending",
          asyncTaskId,
          completedAt: undefined,
        });
        asyncDetected = true;
        // 注入占位 tool result（让 LLM 下一轮能 match tool_call_id）
        // 注：这里用 childToolId 作为 tool_call_id 注入（漏洞 3 修复：async 完成时用同样 id 注入真结果）
        const placeholder = JSON.stringify({
          asyncTaskId,
          status: parsedResult.status,
          note: "异步任务已提交，结果将由 BxdcbotRunScheduler 注入",
          pollUrl: `/api/async-tasks/${asyncTaskId}/wait`,
        });
        messages.push({
          role: "tool",
          tool_call_id: childToolId,
          content: placeholder,
        });
        console.log(`[runSubPlanner] runId=${run.runId} async placeholder pushed: tool=${tool.name}, tool_call_id=${childToolId}, asyncTaskId=${asyncTaskId}`);
        emitToolTraceEvent({
          type: "tool_status",
          toolId: childToolId,
          toolName: tool.name,
          displayName: childDisplayName,
          kind: describeGatewayExtendedTool(tool.name)?.kind || "tool",
          status: "running",
          parentToolId,
          parentToolName,
          summary: `async task ${asyncTaskId} submitted`,
          result: parsedResult,
        });
        // 退出 6 轮循环（让 scheduler 等 async 完成）
        break;
      }

      // sync skill：注入 tool result
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
      console.log(`[runSubPlanner] runId=${run.runId} sync tool result pushed: tool=${tool.name}, tool_call_id=${resolvedCallId}, content_len=${result.length}`);
      // 追踪子技能结果
      run.subTaskResults.push({
        skillName: tool.name,
        status: "completed",
        result,
        completedAt: new Date().toISOString(),
      });
    }  // end for (toolCall of toolCalls)
    });  // end bxdcbotActiveRunStorage.run()
    if (unauthorizedError) return unauthorizedError;
    if (asyncDetected) {
      break;  // 退出外层 6 轮 for
    }
  }  // end outer for (rounds)

  // 保存 messages 到 run（续周期用）
  run.messages = messages;

  if (asyncDetected) {
    run.status = "awaiting_async";
    // 决策 12：返回明确指令告诉外层 LLM 停止工具调用
    // AWAITING_ASYNC 状态由 BxdcbotRunScheduler 处理续周期，LLM 不需要再调任何 tool
    return JSON.stringify({
      status: "AWAITING_ASYNC",
      runId: run.runId,
      pendingCount: run.pendingAsyncTaskIds.size,
      instruction: "DO_NOT_INVOKE_ANY_TOOL",
      finalAnswer: `已为您提交 ${run.pendingAsyncTaskIds.size} 个异步任务，结果将在完成后由系统自动回灌并继续回复。请不要再次调用任何工具，等待后续回复。`,
      note: "Bxdcbot run 调了异步 skill，等真结果回来后由 BxdcbotRunScheduler 续调",
    });
  }

  // 跑完 6 轮但没出 async + 没出文本 → 6 轮触顶
  return JSON.stringify({ error: "OPENCLAW skill exceeded the maximum planning steps." });
}

/**
 * resumeBxdcbotPlanner —— 由 BxdcbotRunResumer 调，重新进入新一个 6 轮子规划周期。
 *
 * 行为：
 * - 从 run.plannerContext 拿回 plannerModel / config / availableTools / parentToolName
 * - 跑一个新周期（runSubPlanner 内部用 run.messages 累积）
 * - 完成后由 BxdcbotRunResumer 决定调 complete
 */
export async function resumeBxdcbotPlanner(run: BxdcbotRun): Promise<string> {
  // Bxdcbot 自规划 mode 续周期也走 AsyncLocalStorage 上下文（不阻塞 func）
  return await bxdcbotPlannerCallContext.run(true, async () => {
    return await resumeBxdcbotPlannerImpl(run);
  });
}

async function resumeBxdcbotPlannerImpl(run: BxdcbotRun): Promise<string> {
  const ctx = run.plannerContext;
  if (!ctx) {
    throw new Error(`runId=${run.runId} has no plannerContext, cannot resume`);
  }
  const { plannerModel, parentToolName, input, config, availableTools } = ctx;

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

  const planner = allowedTools.length > 0
    ? (plannerModel && typeof plannerModel.bindTools === "function" ? plannerModel.bindTools(allowedTools) : null)
    : plannerModel;
  if (!planner || typeof planner.invoke !== "function") {
    throw new Error("planner model is unavailable for resume");
  }

  const parentToolId = run.parentToolId;
  return await runSubPlanner(run, planner, parentToolName, input, config, availableTools, allowedTools, parentToolId);
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
    /**
     * 对话级别持久 ID（gateway 的 conversations.conversation_id）。
     * 调 gateway 时 X-Session-Id 优先用这个，而不是 sessionId（sessionId 是前端给每条消息
     * 临时生成的 timestamp+random，用作 SSE 取消/思考状态隔离；不应该作为 async_tasks.session_id
     * 持久化——否则 AsyncTaskChatReplyService 回灌消息时 conversation_id 不匹配 conversations 表）。
     */
    conversationId?: string;
    /** 对话级别 Skill 过滤：有值时仅加载匹配 ID 的 Extension Skill，undefined 或 [] 时全量加载 */
    enabledSkillIds?: number[];
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

    // 按对话配置过滤 Extension Skill
    // undefined = 不传该字段（旧客户端）→ 全量加载
    // [] = 明确空数组 → 无 Extension Skill
    const filteredSkills = options?.enabledSkillIds !== undefined
      ? extensionSkills.filter((s) => options.enabledSkillIds!.includes(s.id))
      : extensionSkills;

    const toolLookup = new Map<string, BindableAgentTool>();
    (options?.availableTools || []).forEach((tool) => {
      toolLookup.set(tool.name, tool);
    });

    // === 第一遍：加载所有 skill 的 config，识别 OPENCLAW 技能及其 allowedTools ===
    const skillConfigs = new Map<number, { workingSkill: GatewaySkill; config: ExtendedSkillConfig; toolName: string }>();
    const coveredToolNames = new Set<string>();

    for (const skill of filteredSkills) {
      let workingSkill = skill;
      let config = skill.configuration ? parseSkillConfig(skill) : {} as ExtendedSkillConfig;
      if (!skill.configuration?.trim()) {
        try {
          const detailResponse = await axios.get(`${gatewayUrl}/api/skills/${skill.id}`, {
            headers: gatewaySkillReadHeaders(apiToken, userId),
          });
          workingSkill = detailResponse.data as GatewaySkill;
          config = parseSkillConfig(workingSkill);
        } catch { /* keep empty config */ }
      }
      const toolName = normalizeToolName(skill.name || `skill_${skill.id}`, skill.id);
      skillConfigs.set(skill.id, { workingSkill, config, toolName });

      // 收集 OPENCLAW 技能的 allowedTools（这些技能的 tool 不应该暴露给外层 LLM）
      const executionMode = normalizeExecutionMode(workingSkill.executionMode);
      if (executionMode === "OPENCLAW" || (config.kind || "").toLowerCase() === "openclaw") {
        const allowed = config.allowedTools || [];
        for (const t of allowed) {
          const normalized = t.trim().replace(/-/g, "_");
          coveredToolNames.add(normalized);
          coveredToolNames.add(t.trim());
        }
      }
    }

    // === 第二遍：创建 tool，OPENCLAW 子技能只加 toolLookup 不暴露给外层 LLM ===
    const resolvedTools: StructuredTool[] = [];
    for (const skill of filteredSkills) {
      const entry = skillConfigs.get(skill.id)!;
      const { workingSkill, config, toolName } = entry;
      registerGatewayToolMetadata(toolName, workingSkill);

      const executionMode = normalizeExecutionMode(workingSkill.executionMode);
      const isOpenClaw = executionMode === "OPENCLAW" || (config.kind || "").toLowerCase() === "openclaw";
      const isCoveredByOpenClaw = !isOpenClaw && coveredToolNames.has(toolName);

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
              // 注入 skill_id / conversationId 等必要字段到 config（executeOpenClawSkill 需要）
              const enrichedConfig = {
                ...currentConfig,
                skill_id: currentSkill.id,
                skillId: currentSkill.id,
                conversationId: options?.conversationId ?? "",
                userId: userId ?? "",
              };
              return await executeOpenClawSkill(
                options?.plannerModel,
                toolName,
                openClawInput,
                enrichedConfig,
                Array.from(toolLookup.values()),
              );
            }

            // All CONFIG skills → unified Gateway execute endpoint
            const executeUrl = `${gatewayUrl}/api/skills/execute`;
            // 优先用 conversationId（gateway 的持久对话 ID），避免 sessionId（per-turn 时间戳）
            // 被存到 async_tasks.session_id 导致 AsyncTaskChatReplyService 回灌消息时找不到 conversations 行
            const sessionIdCandidate = options?.conversationId
              ?? (options?.sessionId ?? runConfig?.configurable?.thread_id
                ? String(options?.sessionId ?? runConfig?.configurable?.thread_id)
                : undefined);
            const executeSessionId = sessionIdCandidate;
            const executeHeaders = gatewaySkillMutationHeaders(apiToken, userId, executeSessionId);
            // Strip the `{payload: ...}` wrapper that extendedPassthroughSkillToolSchema
            // (ensureObjectType) injects for DeepSeek JSON-Schema compatibility, so the
            // Gateway never sees "payload" as a real parameter name. OPENCLAW path
            // unwraps `input` similarly above; CONFIG (passthrough) unwraps `payload` here.
            // `payload` is reserved as a wrapper key — skill parameter contracts must not
            // declare a parameter named "payload" (use schemaProperties if you need it).
            let parameters: Record<string, unknown> = execInput && typeof execInput === "object" && !Array.isArray(execInput)
              ? (execInput as Record<string, unknown>)
              : {};
            if (
              parameters
              && Object.keys(parameters).length === 1
              && "payload" in parameters
              && parameters.payload
              && typeof parameters.payload === "object"
              && !Array.isArray(parameters.payload)
            ) {
              parameters = parameters.payload as Record<string, unknown>;
            }

            const executePayload = { skillId: currentSkill.id, parameters };

            // 如果在 Bxdcbot 子规划上下文中，注入 parentToolId/parentSkillId
            const activeCtx = bxdcbotActiveRunStorage.getStore();
            if (activeCtx) {
              Object.assign(executePayload, {
                parentToolId: activeCtx.parentToolId,
                parentSkillId: activeCtx.parentSkillId,
              });
            }

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
      // Bxdcbot 子技能：只加到 toolLookup（内部可用），不暴露给外层 chat LLM
      if (isOpenClaw) {
        resolvedTools.push(structuredTool);
      } else if (isCoveredByOpenClaw) {
        // 子技能被 Bxdcbot 的 allowedTools 覆盖，不暴露给 chat LLM
        console.log(`[gateway-tools] ${toolName} covered by OPENCLAW, internal only`);
      } else {
        resolvedTools.push(structuredTool);
      }
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
