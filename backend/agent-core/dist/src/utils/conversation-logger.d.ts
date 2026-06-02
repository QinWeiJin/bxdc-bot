export interface ConversationLog {
    userId: string;
    sessionId: string;
    traceId?: string;
    responseDurationSeconds?: number;
    llmRounds?: number;
    toolCallRounds?: number;
    isExceedMaxRound?: number;
    isSuccess?: number;
    status?: string;
    finishReason?: string;
    llmModel?: string;
    skillName?: string;
    toolName?: string;
    logLevel?: string;
    logMessage?: string;
    errorMessage?: string;
    errorStackTrace?: string;
    requestData?: string;
    responseData?: string;
    conversationContent?: string;
    totalTokens?: number;
    promptTokens?: number;
    completionTokens?: number;
    agentVersion?: string;
    environment?: string;
}
export interface ToolCallLog {
    traceId: string;
    sessionId: string;
    userId?: string;
    toolName: string;
    skillName?: string;
    toolCallId?: string;
    requestParams?: string;
    responseResult?: string;
    status?: string;
    errorMessage?: string;
    startTime?: string;
    endTime?: string;
    durationMs?: number;
    llmInputTokens?: number;
    llmOutputTokens?: number;
    httpStatus?: number;
    gatewayUrl?: string;
}
export declare class ConversationLogger {
    private gatewayUrl;
    constructor();
    logConversation(log: ConversationLog): Promise<void>;
    logToolCall(log: ToolCallLog): Promise<void>;
}
