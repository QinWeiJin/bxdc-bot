import { DynamicTool, Tool, DynamicStructuredTool, StructuredTool } from "@langchain/core/tools";
import { z } from "zod";
export declare function getAgentBuiltinSkillDispatch(): "legacy" | "gateway";
export type BuiltinSkillDispatch = "legacy" | "gateway";
export declare const computeToolInputSchema: z.ZodObject<{
    operation: z.ZodEnum<["add", "subtract", "multiply", "divide", "factorial", "square", "sqrt", "timestamp_to_date", "date_diff_days"]>;
    operands: z.ZodArray<z.ZodUnion<[z.ZodNumber, z.ZodString]>, "many">;
}, "strip", z.ZodTypeAny, {
    operation?: "add" | "subtract" | "multiply" | "divide" | "factorial" | "square" | "sqrt" | "timestamp_to_date" | "date_diff_days";
    operands?: (string | number)[];
}, {
    operation?: "add" | "subtract" | "multiply" | "divide" | "factorial" | "square" | "sqrt" | "timestamp_to_date" | "date_diff_days";
    operands?: (string | number)[];
}>;
declare const serverLookupToolInputSchema: z.ZodObject<{
    serverName: z.ZodString;
}, "strip", z.ZodTypeAny, {
    serverName?: string;
}, {
    serverName?: string;
}>;
export declare const apiCallerToolInputSchema: z.ZodObject<{
    url: z.ZodString;
    method: z.ZodDefault<z.ZodEnum<["GET", "POST", "PUT", "DELETE", "PATCH"]>>;
    headers: z.ZodOptional<z.ZodRecord<z.ZodString, z.ZodString>>;
    body: z.ZodOptional<z.ZodAny>;
}, "strip", z.ZodTypeAny, {
    url?: string;
    method?: "GET" | "POST" | "PUT" | "DELETE" | "PATCH";
    headers?: Record<string, string>;
    body?: any;
}, {
    url?: string;
    method?: "GET" | "POST" | "PUT" | "DELETE" | "PATCH";
    headers?: Record<string, string>;
    body?: any;
}>;
export declare function formatToolError(error: unknown): string;
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
    avatar?: string;
    templatePlaceholders?: string[];
    schemaProperties?: Record<string, {
        type: string;
        description?: string;
        default?: unknown;
        enum?: (string | number)[];
        const?: unknown;
    }>;
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
    timeoutSeconds?: number;
    asyncPoll?: AsyncPollConfig;
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
    pollEndpoint: string;
    idJsonPath?: string;
    pollMethod?: string;
    pollIntervalMs?: number;
    pollIntervalSeconds?: number;
    maxWaitMs?: number;
    maxWaitSeconds?: number;
    completionJsonPath?: string;
    completionValue?: string;
    failedValues?: string[];
    resultJsonPath?: string;
    pollHeaders?: Record<string, string>;
}
export declare function readPreset(config: ExtendedSkillConfig): string | undefined;
export declare function describeGatewayExtendedTool(toolName: string): {
    displayName: string;
    kind: 'skill' | 'tool';
    executionMode?: string;
    executionLabel?: string;
} | null;
export declare function normalizeParameterBindingValue(raw: unknown): "query" | "jsonBody" | "formBody" | undefined;
export declare function normalizeExtendedConfig(cfg: ExtendedSkillConfig): ExtendedSkillConfig;
export declare function parseSkillConfig(skill: GatewaySkill): ExtendedSkillConfig;
export declare function normalizeParameterContractRequired(contract: Record<string, unknown>): Record<string, unknown>;
export declare function normalizeGeneratedOperation(value: string): string;
export declare function sanitizeConfigForDisplay(config: ExtendedSkillConfig): ExtendedSkillConfig;
export type BindableAgentTool = Tool | DynamicTool | StructuredTool;
export declare function gatewaySkillMutationHeaders(apiToken: string, userId?: string, sessionId?: string): Record<string, string>;
export declare function gatewaySkillReadHeaders(apiToken: string, userId?: string): Record<string, string>;
export declare function loadGatewayExtendedTools(gatewayUrl: string, apiToken: string, userId?: string, options?: {
    plannerModel?: any;
    availableTools?: BindableAgentTool[];
    sessionId?: string;
}): Promise<StructuredTool[]>;
export declare function buildConfirmedToolInputString(args: unknown): string;
export declare function buildConfirmedToolArgs(args: unknown): Record<string, unknown>;
export declare function invokeExtendedSkillWithConfirmed(gatewayUrl: string, apiToken: string, userId: string | undefined, toolName: string, toolArguments: unknown, options: {
    plannerModel: any;
    availableTools: BindableAgentTool[];
}): Promise<string>;
export declare class JavaComputeTool extends DynamicStructuredTool<typeof computeToolInputSchema> {
    constructor(gatewayUrl: string, apiToken: string, options?: {
        dispatch?: BuiltinSkillDispatch;
    });
}
export declare class JavaServerLookupTool extends DynamicStructuredTool<typeof serverLookupToolInputSchema> {
    constructor(gatewayUrl: string, apiToken: string, userId?: string);
}
export declare class JavaApiTool extends DynamicStructuredTool<typeof apiCallerToolInputSchema> {
    constructor(gatewayUrl: string, apiToken: string, options?: {
        dispatch?: BuiltinSkillDispatch;
    });
}
export {};
