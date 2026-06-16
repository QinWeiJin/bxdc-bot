/**
 * 技能执行工具模块
 * 
 * 模块职责：
 * - 根据搜索结果创建子 Agent 并执行特定技能
 * - 子 Agent 加载指定的技能列表进行执行
 * - 执行完成后返回结果给主 Agent
 * 
 * 设计说明：
 * - 接收技能 ID 列表和用户输入
 * - 创建临时子 Agent，仅加载指定的技能
 * - 执行子 Agent 并获取结果
 * - 返回格式化的执行结果给主 Agent
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
});

/**
 * 技能执行工具（内置名：`execute_skill_with_context`）
 * 
 * 根据指定的技能 ID 列表创建子 Agent，并执行用户任务。
 * 子 Agent 仅加载指定的技能，执行完成后返回结果给主 Agent。
 */
export class ExecuteSkillWithContextTool extends DynamicStructuredTool<typeof executeSkillInputSchema> {
  constructor(
    private readonly gatewayUrl: string,
    private readonly apiToken: string,
    private readonly openAiApiKey: string,
    private readonly llmConfig?: { modelName?: string; baseUrl?: string },
    private readonly userId?: string
  ) {
    super({
      name: "execute_skill_with_context",
      description:
        "Create a sub-agent with specific skills and execute the user's task. " +
        "WORKFLOW: First call search_tools to find relevant skills, then extract the 'id' numbers " +
        "from the returned skills array and pass them here as skillIds. " +
        "The sub-agent will be dynamically created with only those specific skills loaded, " +
        "executes the userInput task, and returns the result. " +
        "After receiving the result, you can continue planning or summarize for the user.",
      schema: executeSkillInputSchema,
      func: async (args) => {
        try {
          const { skillIds, userInput } = args;

          // 创建子 Agent，仅加载指定的技能
          const { agent, plannerModel } = await AgentFactory.createSubAgent(
            gatewayUrl,
            apiToken,
            openAiApiKey,
            skillIds, // 仅加载指定的技能
            {
              modelName: llmConfig?.modelName || "gpt-4",
              baseUrl: llmConfig?.baseUrl,
            },
            userId
          );

          // 构建子 Agent 的消息历史
          const systemPrompt = new SystemMessage(buildStaticSystemPrompt());
          const humanMessage = new HumanMessage(userInput);
          
          const messages: BaseMessage[] = [systemPrompt, humanMessage];

          // 执行子 Agent
          const result = await agent.invoke({ messages });

          // 提取所有消息
          const finalMessages = result.messages as BaseMessage[];
          const lastMessage = finalMessages[finalMessages.length - 1];
          
          // 提取最终输出
          let output = "";
          if (lastMessage) {
            if (typeof lastMessage.content === "string") {
              output = lastMessage.content;
            } else if (Array.isArray(lastMessage.content)) {
              output = lastMessage.content
                .map((part) => typeof part === "string" ? part : part?.text || "")
                .join("");
            }
          }

          // 如果没有直接输出，尝试从工具调用结果中提取
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

          // 提取所有工具调用记录
          const toolCalls: Array<{
            toolName: string;
            input: any;
            output: string;
            timestamp: string;
          }> = [];

          for (const msg of finalMessages) {
            const type = msg._getType?.() ?? (msg as any).type ?? "";
            
            // 提取 AI 消息中的工具调用（输入）
            if (type === "ai" || type === "AIMessageChunk") {
              const calls = (msg as any).tool_calls ?? [];
              for (const call of calls) {
                toolCalls.push({
                  toolName: call.name,
                  input: call.args,
                  output: "", // 输出会在 tool 消息中匹配
                  timestamp: new Date().toISOString(),
                });
              }
            }
            
            // 提取工具消息（输出）
            if (type === "tool") {
              const toolName = (msg as any).name;
              const toolContent = (msg as any).content;
              
              // 找到对应的工具调用记录，填充输出
              const lastCall = toolCalls.find(tc => tc.toolName === toolName && tc.output === "");
              if (lastCall) {
                lastCall.output = typeof toolContent === "string" ? toolContent : JSON.stringify(toolContent);
              }
            }
          }

          return JSON.stringify({
            status: "SUCCESS",
            message: "Sub-agent execution completed successfully",
            executedSkillIds: skillIds,
            result: output || "No output generated",
            toolCalls, // 包含所有工具调用历史
          });
        } catch (error) {
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