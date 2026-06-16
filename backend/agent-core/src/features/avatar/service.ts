import { ChatOpenAI } from "@langchain/openai";
import { composeOpenAiCompatibleFetch } from "../../utils/llm-request-role-normalize";
import { GENERATE_AVATAR_SYSTEM_PROMPT, GREETING_TEMPLATES } from "./prompts";
import { SystemMessage, HumanMessage } from "@langchain/core/messages";

export class AvatarService {
  private llm: ChatOpenAI;

  constructor(apiKey: string, modelName: string = "gpt-4", baseUrl?: string) {
    this.llm = new ChatOpenAI({
      apiKey,
      modelName: modelName,
      configuration: {
        ...(baseUrl ? { baseURL: baseUrl.replace(/\/+$/, "") } : {}),
        fetch: composeOpenAiCompatibleFetch(),
      },
      temperature: 0.7, // Higher creativity for avatar/greeting
      timeout: 30000, // 30秒超时
    });
  }

  async generateAvatar(nickname: string): Promise<string> {
    try {
      const response = await this.llm.invoke([
        new SystemMessage(GENERATE_AVATAR_SYSTEM_PROMPT),
        new HumanMessage(`Nickname: ${nickname}`),
      ]);
      
      const content = typeof response.content === 'string' 
        ? response.content 
        : Array.isArray(response.content) 
          ? (response.content[0] as any).text // Fallback for complex content
          : '';
          
      // Clean up response to get just the emoji
      // This regex is a simple approximation for emojis, might need refinement but good enough for now
      // It keeps emoji characters and removes others.
      // If the LLM follows instructions, it should be just an emoji.
      const emoji = content.trim();
      return emoji || '👤'; // Fallback if empty
    } catch (error) {
      console.error("Error generating avatar:", error);
      return '👤'; // Fallback on error
    }
  }

  async generateGreeting(nickname: string, avatar: string): Promise<string> {
    const template = GREETING_TEMPLATES[Math.floor(Math.random() * GREETING_TEMPLATES.length)];
    return template.replace("{nickname}", nickname).replace("{avatar}", avatar);
  }
}
