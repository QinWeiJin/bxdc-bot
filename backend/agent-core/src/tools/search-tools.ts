/**
 * 技能搜索工具模块
 * 
 * 模块职责：
 * - 提供技能搜索功能，根据用户问题检索相关技能
 * - 返回匹配的技能列表，供主 Agent 决策使用
 * - 支持基于关键词、描述的模糊搜索
 * 
 * 设计说明：
 * - 从 Gateway 获取所有可用技能
 * - 根据用户查询关键词进行匹配
 * - 返回技能的基本信息（id、name、description、type）
 * - 主 Agent 使用此工具后，可根据结果创建子 Agent 执行特定技能
 * 
 * @module SearchTools
 * @author Agent Core Team
 * @since 1.0.0
 */

import { DynamicStructuredTool } from "@langchain/core/tools";
import { z } from "zod";
import axios from "axios";
import { formatToolError, type GatewaySkill } from "./java-skills";

const searchToolsInputSchema = z.object({
  query: z
    .string()
    .min(1)
    .describe("The user's question or task description to search for relevant skills."),
});

/**
 * 技能搜索工具（内置名：`search_tools`）
 * 
 * 根据用户问题搜索相关的 Extension Skill，返回匹配的技能列表。
 * 主 Agent 使用此工具后，可以选择合适的技能创建子 Agent 执行。
 */
export class SearchToolsTool extends DynamicStructuredTool<typeof searchToolsInputSchema> {
  constructor(
    private readonly gatewayUrl: string,
    private readonly apiToken: string,
    private readonly userId?: string
  ) {
    super({
      name: "search_tools",
      description:
        "Search for relevant system skills based on the user's question or task description. " +
        "Returns JSON with status, message, and a skills array. Each skill object contains: " +
        "{ id: number, name: string, description: string, type: string, executionMode: string }. " +
        "Use this tool when you need to find the right built-in skill to execute a specific task. " +
        "After getting results, extract the 'id' fields from the skills array and pass them to execute_skill_with_context " +
        "as the skillIds parameter to create a sub-agent with those specific system skills.",
      schema: searchToolsInputSchema,
      func: async (args) => {
        try {
          const headers: Record<string, string> = {
            "X-Agent-Token": apiToken,
          };
          if (userId) {
            headers["X-User-Id"] = userId;
          }

          // 获取系统技能（skill_owner_type=2）
          const response = await axios.get(`${gatewayUrl}/api/skills/by-owner-type`, { 
            headers,
            params: { ownerType: 2 }
          });
          const skills = Array.isArray(response.data) ? response.data as GatewaySkill[] : [];
          
          // 过滤已启用的系统技能
          const enabledSkills = skills.filter(
            (skill) => skill.enabled
          );

          if (enabledSkills.length === 0) {
            return JSON.stringify({
              status: "NO_SKILLS_FOUND",
              message: "No extension skills available.",
              skills: [],
            });
          }

          const query = args.query.toLowerCase().trim();
          
          // 基于关键词匹配技能
          const matchedSkills = enabledSkills.filter((skill) => {
            const nameMatch = skill.name?.toLowerCase().includes(query);
            const descMatch = skill.description?.toLowerCase().includes(query);
            
            // 检查配置中的关键词
            let configMatch = false;
            if (skill.configuration) {
              try {
                const config = JSON.parse(skill.configuration);
                const configStr = JSON.stringify(config).toLowerCase();
                configMatch = configStr.includes(query);
              } catch {
                // ignore parse error
              }
            }

            return nameMatch || descMatch || configMatch;
          });

          // 如果没有精确匹配，返回所有技能供用户选择
          const results = matchedSkills.length > 0 ? matchedSkills : enabledSkills;

          // 构建返回结果
          const skillList = results.map((skill) => ({
            id: skill.id,
            name: skill.name,
            description: skill.description,
            type: skill.type,
            executionMode: skill.executionMode,
            requiresConfirmation: skill.requiresConfirmation,
            avatar: skill.avatar,
          }));

          return JSON.stringify({
            status: "SUCCESS",
            message: `Found ${skillList.length} relevant skill(s)`,
            skills: skillList,
          });
        } catch (error) {
          return JSON.stringify({
            status: "ERROR",
            message: `Error searching skills: ${formatToolError(error)}`,
            skills: [],
          });
        }
      },
    });
  }
}