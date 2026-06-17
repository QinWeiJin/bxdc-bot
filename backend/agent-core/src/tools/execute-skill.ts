/**
 * 技能执行工具模块
 * 
 * 模块职责：
 * - 根据搜索结果创建子 Agent 并执行特定技能
 * - 子 Agent 加载指定的技能列表进行执行
 * - 支持多步操作，复用子 Agent 实例，保持对话状态
 * - 执行完成后返回结果给主 Agent
 * - 支持流式返回每一步工具调用结果
 * 
 * 设计说明：
 * - 接收技能 ID 列表和用户输入
 * - 创建子 Agent 实例并缓存，支持多步复用
 * - 使用流式执行子 Agent，实时返回每步结果
 * - 返回格式化的执行结果给主 Agent
 * - 缓存的子 Agent 有过期时间，自动清理
 * 
 * @module ExecuteSkill
 * @author Agent Core Team
 * @since 1.0.0
 */

import { DynamicStructuredTool } from "@langchain/core/tools";
import { z } from "zod";
import { AgentFactory } from "../agent/agent";
import { HumanMessage, SystemMessage, type BaseMessage } from "@langchain/core/messages";
import { buildStaticSystemPrompt, Prompts } from "../prompts";
import { unwrapLangGraphStreamPayload } from "../controller/agent.controller";

const executeSkillInputSchema = z.object({
  skillIds: z
    .array(z.number())
    .min(1)
    .describe("List of skill IDs to load in the sub-agent for execution. " +
      "You can extract these IDs from the skills array returned by search_tools (each skill has an 'id' field)."),
  userInput: z
    .string()
    .min(1)
    .describe("The user's input or task to be executed by the sub-agent."),
  continueConversation: z
    .boolean()
    .optional()
    .default(false)
    .describe("Set to true to continue the previous conversation with the same sub-agent. " +
      "When true, the sub-agent will reuse the previous conversation history. " +
      "When false (default), a new conversation will start."),
});

export type SubAgentStreamCallback = (event: {
  type: 'tool_call_start' | 'tool_call_end' | 'thinking' | 'error';
  toolName?: string;
  input?: any;
  output?: string;
  message?: string;
}) => void;

interface CachedSubAgent {
  agent: any;
  messages: BaseMessage[];
  createdAt: number;
  skillIds: number[];
}

const subAgentCache = new Map<string, CachedSubAgent>();
const CACHE_EXPIRATION_MS = 5 * 60 * 1000;

function getCacheKey(skillIds: number[], userId?: string): string {
  return `${userId || 'default'}_${skillIds.sort().join('_')}`;
}

function cleanupExpiredAgents(): void {
  const now = Date.now();
  for (const [key, cached] of subAgentCache.entries()) {
    if (now - cached.createdAt > CACHE_EXPIRATION_MS) {
      subAgentCache.delete(key);
    }
  }
}

setInterval(cleanupExpiredAgents, 60 * 1000);

/**
 * 技能执行工具（内置名：`execute_skill_with_context`）
 * 
 * 根据指定的技能 ID 列表创建子 Agent，并执行用户任务。
 * 子 Agent 支持多步操作，会自动缓存和复用，保持对话状态。
 * 支持流式返回每一步工具调用结果。
 */
export class ExecuteSkillWithContextTool extends DynamicStructuredTool<typeof executeSkillInputSchema> {
  constructor(
    private readonly gatewayUrl: string,
    private readonly apiToken: string,
    private readonly openAiApiKey: string,
    private readonly llmConfig?: { modelName?: string; baseUrl?: string },
    private readonly userId?: string,
    private readonly streamCallback?: SubAgentStreamCallback
  ) {
    super({
      name: "execute_skill_with_context",
      description:
        "Create or reuse a sub-agent with specific skills and execute the user's task. " +
        "WORKFLOW: First call search_tools to find relevant skills, then extract the 'id' numbers " +
        "from the returned skills array and pass them here as skillIds. " +
        "The sub-agent will be dynamically created with only those specific skills loaded, " +
        "executes the userInput task, and returns the result. " +
        "SUPPORT MULTI-STEP: The sub-agent is cached and can handle multiple steps. " +
        "For subsequent steps with the same skills, set continueConversation to true " +
        "to continue the conversation instead of creating a new sub-agent. " +
        "After receiving the result, you can continue planning or summarize for the user.",
      schema: executeSkillInputSchema,
      func: async (args) => {
        try {
          const { skillIds, userInput, continueConversation } = args;
          const cacheKey = getCacheKey(skillIds, userId);

          let agent: any;
          let messages: BaseMessage[];

          if (continueConversation && subAgentCache.has(cacheKey)) {
            const cached = subAgentCache.get(cacheKey)!;
            agent = cached.agent;
            messages = [...cached.messages];
            messages.push(new HumanMessage(userInput));
            this.streamCallback?.({
              type: 'thinking',
              message: `Reusing cached sub-agent with skillIds: ${skillIds.join(', ')}`,
            });
          } else {
            const { agent: newAgent } = await AgentFactory.createSubAgent(
              gatewayUrl,
              apiToken,
              openAiApiKey,
              skillIds,
              {
                modelName: llmConfig?.modelName || "gpt-4",
                baseUrl: llmConfig?.baseUrl,
              },
              userId
            );
            agent = newAgent;
            messages = [
              new SystemMessage(buildStaticSystemPrompt()),
              new HumanMessage(userInput),
            ];
            subAgentCache.set(cacheKey, {
              agent,
              messages,
              createdAt: Date.now(),
              skillIds,
            });
            this.streamCallback?.({
              type: 'thinking',
              message: `Created new sub-agent with skillIds: ${skillIds.join(', ')}`,
            });
          }

          const toolCalls: Array<{
            toolName: string;
            input: any;
            output: string;
            timestamp: string;
          }> = [];

          let output = "";
          let lastPayload: any = null;

          const stream = await agent.stream({ messages });
          const iterator = stream[Symbol.asyncIterator]();

          while (true) {
            const { value: raw, done } = await iterator.next();
            if (done) break;

            const payload = unwrapLangGraphStreamPayload(raw);
            lastPayload = payload;
            const streamMessages = payload?.messages || [];

            for (const msg of streamMessages) {
              const type = msg._getType?.() ?? (msg as any).type ?? "";

              if (type === "ai" || type === "AIMessageChunk") {
                const calls = (msg as any).tool_calls ?? [];
                for (const call of calls) {
                  toolCalls.push({
                    toolName: call.name,
                    input: call.args,
                    output: "",
                    timestamp: new Date().toISOString(),
                  });
                  this.streamCallback?.({
                    type: 'tool_call_start',
                    toolName: call.name,
                    input: call.args,
                  });
                }
              }

              if (type === "tool") {
                const toolName = (msg as any).name;
                const toolContent = (msg as any).content;
                const toolOutput = typeof toolContent === "string" ? toolContent : JSON.stringify(toolContent);

                const lastCall = toolCalls.find(tc => tc.toolName === toolName && tc.output === "");
                if (lastCall) {
                  lastCall.output = toolOutput;
                }

                this.streamCallback?.({
                  type: 'tool_call_end',
                  toolName: toolName,
                  input: lastCall?.input,
                  output: toolOutput,
                });
              }

              if ((type === "ai" || type === "AIMessageChunk") && !((msg as any).tool_calls?.length > 0)) {
                const content = typeof msg.content === "string" ? msg.content : "";
                if (content) {
                  this.streamCallback?.({
                    type: 'thinking',
                    message: content,
                  });
                }
              }
            }
          }

          if (lastPayload?.messages) {
            subAgentCache.set(cacheKey, {
              agent,
              messages: lastPayload.messages as BaseMessage[],
              createdAt: Date.now(),
              skillIds,
            });
          }

          const finalMessages = lastPayload?.messages || [];
          const lastMessage = finalMessages[finalMessages.length - 1];
          
          if (lastMessage) {
            if (typeof lastMessage.content === "string") {
              output = lastMessage.content;
            } else if (Array.isArray(lastMessage.content)) {
              output = lastMessage.content
                .map((part) => typeof part === "string" ? part : part?.text || "")
                .join("");
            }
          }

          if (!output) {
            for (let i = finalMessages.length - 1; i >= 0; i--) {
              const msg = finalMessages[i];
              if (msg._getType?.() === "tool" || msg.type === "tool") {
                const toolContent = (msg as any).content;
                if (typeof toolContent === "string") {
                  output = toolContent;
                  break;
                }
              }
            }
          }

          return JSON.stringify({
            status: "SUCCESS",
            message: "Sub-agent execution completed successfully",
            executedSkillIds: skillIds,
            result: output || "No output generated",
            toolCalls,
            conversationCached: subAgentCache.has(cacheKey),
          });
        } catch (error) {
          this.streamCallback?.({
            type: 'error',
            message: `Error executing skill: ${error instanceof Error ? error.message : String(error)}`,
          });
          return JSON.stringify({
            status: "ERROR",
            message: `Error executing skill: ${error instanceof Error ? error.message : String(error)}`,
            executedSkillIds: args.skillIds,
            result: "",
          });
        }
      },
    });
  }
}