"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.AgentFactory = void 0;
const prebuilt_1 = require("@langchain/langgraph/prebuilt");
const langgraph_1 = require("@langchain/langgraph");
const openai_1 = require("@langchain/openai");
const llm_request_role_normalize_1 = require("../utils/llm-request-role-normalize");
const java_skills_1 = require("../tools/java-skills");
const manage_tasks_1 = require("../tools/manage-tasks");
const tasks_state_1 = require("./tasks-state");
const sharedAgentCheckpointer = new langgraph_1.MemorySaver();
class AgentFactory {
    static async createAgent(gatewayUrl, apiToken, openAiApiKey, config, skillManager, userId) {
        const openAiConfiguration = {};
        if (config?.baseUrl?.trim()) {
            openAiConfiguration.baseURL = config.baseUrl.replace(/\/+$/, "");
        }
        openAiConfiguration.fetch = (0, llm_request_role_normalize_1.composeOpenAiCompatibleFetch)({
            userId,
            sessionId: config?.sessionId,
        });
        const model = new openai_1.ChatOpenAI({
            modelName: config?.modelName || "gpt-4",
            apiKey: openAiApiKey,
            ...(Object.keys(openAiConfiguration).length > 0
                ? { configuration: openAiConfiguration }
                : {}),
            temperature: 0,
            callbacks: config?.callbacks,
            streaming: true,
        });
        const exposeSshExecutor = !userId?.trim()
            || process.env.AGENT_EXPOSE_SSH_EXECUTOR === "1"
            || process.env.AGENT_EXPOSE_SSH_EXECUTOR === "true";
        const builtinDispatch = (0, java_skills_1.getAgentBuiltinSkillDispatch)();
        const baseTools = [
            ...(exposeSshExecutor
                ? [new java_skills_1.JavaSshTool(gatewayUrl, apiToken, userId, { dispatch: builtinDispatch })]
                : []),
            new java_skills_1.JavaSkillGeneratorTool(gatewayUrl, apiToken, userId),
            new java_skills_1.JavaComputeTool(gatewayUrl, apiToken, { dispatch: builtinDispatch }),
            new java_skills_1.JavaLinuxScriptTool(gatewayUrl, apiToken, userId),
            new java_skills_1.JavaServerLookupTool(gatewayUrl, apiToken, userId),
        ];
        const gatewayExtendedTools = await (0, java_skills_1.loadGatewayExtendedTools)(gatewayUrl, apiToken, userId, {
            plannerModel: model,
            availableTools: baseTools,
        });
        const tools = [
            ...baseTools,
            ...gatewayExtendedTools,
            ...(skillManager?.getLangChainTools() || []),
            new manage_tasks_1.ManageTasksTool(),
        ];
        const agent = (0, prebuilt_1.createReactAgent)({
            llm: model,
            tools,
            stateSchema: tasks_state_1.AgentAnnotation,
            preModelHook: tasks_state_1.preModelHook,
            checkpointer: sharedAgentCheckpointer,
        });
        return { agent, plannerModel: model, baseTools };
    }
}
exports.AgentFactory = AgentFactory;
//# sourceMappingURL=agent.js.map