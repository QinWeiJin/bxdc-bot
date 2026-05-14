export declare class OptimizeTextService {
    private llm;
    constructor(apiKey: string, modelName?: string, baseUrl?: string);
    optimize(fieldId: string, currentText: string, context?: string): Promise<{
        optimizedText: string;
        explanation: string;
    }>;
    private fetchPrompt;
}
