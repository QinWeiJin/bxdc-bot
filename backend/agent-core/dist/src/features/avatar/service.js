"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.AvatarService = void 0;
const openai_1 = require("@langchain/openai");
const llm_request_role_normalize_1 = require("../../utils/llm-request-role-normalize");
const prompts_1 = require("./prompts");
const messages_1 = require("@langchain/core/messages");
class AvatarService {
    llm;
    greetingCache = new Map();
    CACHE_TTL = 1000 * 60 * 60;
    constructor(apiKey, modelName = "gpt-4", baseUrl) {
        this.llm = new openai_1.ChatOpenAI({
            apiKey,
            modelName: modelName,
            configuration: {
                ...(baseUrl ? { baseURL: baseUrl.replace(/\/+$/, "") } : {}),
                fetch: (0, llm_request_role_normalize_1.composeOpenAiCompatibleFetch)(),
            },
            temperature: 0.7,
            timeout: 30000,
        });
    }
    async generateAvatar(nickname) {
        try {
            const response = await this.llm.invoke([
                new messages_1.SystemMessage(prompts_1.GENERATE_AVATAR_SYSTEM_PROMPT),
                new messages_1.HumanMessage(`Nickname: ${nickname}`),
            ]);
            const content = typeof response.content === 'string'
                ? response.content
                : Array.isArray(response.content)
                    ? response.content[0].text
                    : '';
            const emoji = content.trim();
            return emoji || '👤';
        }
        catch (error) {
            console.error("Error generating avatar:", error);
            return '👤';
        }
    }
    async generateGreeting(nickname, avatar) {
        const cacheKey = `${nickname}:${avatar}`;
        const cached = this.greetingCache.get(cacheKey);
        if (cached && (Date.now() - cached.timestamp < this.CACHE_TTL)) {
            console.log(`[AvatarService] Returning cached greeting for ${nickname}`);
            return cached.content;
        }
        const start = Date.now();
        try {
            console.log(`[AvatarService] Generating greeting for ${nickname}...`);
            const defaultGreeting = `欢迎你，${nickname} ${avatar}！很高兴见到你。`;
            const responsePromise = this.llm.invoke([
                new messages_1.SystemMessage(prompts_1.GENERATE_GREETING_SYSTEM_PROMPT),
                new messages_1.HumanMessage(`User: ${nickname} (Avatar: ${avatar})`),
            ]);
            const response = await Promise.race([
                responsePromise,
                new Promise((resolve) => setTimeout(() => resolve(null), 3000))
            ]);
            if (!response) {
                console.log(`[AvatarService] Greeting generation timed out (3s), returning default`);
                responsePromise.then(res => {
                    const content = (typeof res.content === 'string'
                        ? res.content
                        : Array.isArray(res.content)
                            ? res.content[0].text
                            : '').trim();
                    if (content) {
                        this.greetingCache.set(cacheKey, { content, timestamp: Date.now() });
                        console.log(`[AvatarService] Late greeting cached for ${nickname}`);
                    }
                }).catch(err => console.warn("[AvatarService] Late generation error:", err instanceof Error ? err.message : err));
                return defaultGreeting;
            }
            const content = (typeof response.content === 'string'
                ? response.content
                : Array.isArray(response.content)
                    ? response.content[0].text
                    : '').trim();
            const duration = Date.now() - start;
            console.log(`[AvatarService] Greeting generated in ${duration}ms`);
            if (content) {
                this.greetingCache.set(cacheKey, { content, timestamp: Date.now() });
            }
            return content || defaultGreeting;
        }
        catch (error) {
            console.warn("[AvatarService] Greeting generation error (background):", error instanceof Error ? error.message : error);
            return `欢迎你，${nickname} ${avatar}！很高兴见到你。`;
        }
    }
}
exports.AvatarService = AvatarService;
//# sourceMappingURL=service.js.map